package io.github.miguelnieto.matcalcitemcp;

import com.github.vlsi.mat.calcite.mcp.McpServerApplication;

import org.eclipse.core.runtime.content.IContentTypeManager;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Runs the MCP server on stdio once the framework is up, and exits the JVM when stdin closes. It replaces the
 * Equinox application entry point of the mat-calcite-plugin, which a MAT install started with
 * {@code -application MatCalcitePlugin.mcp}.
 */
public class McpActivator implements BundleActivator {
  private static final long PLATFORM_WAIT_MILLIS = 30_000;

  private volatile McpServerApplication server;

  @Override
  public void start(BundleContext context) {
    // Not on the framework's start thread, which must return; the server runs until stdin closes
    Thread thread = new Thread(() -> run(context), "MCP stdio");
    thread.start();
  }

  private void run(BundleContext context) {
    int exitCode = 0;
    try {
      // MAT looks up a dump's content type to pick its parser; the manager is a DS component that registers
      // asynchronously, so a dump opened before it exists would fail
      ServiceTracker<IContentTypeManager, IContentTypeManager> contentTypes =
          new ServiceTracker<>(context, IContentTypeManager.class, null);
      contentTypes.open();
      if (contentTypes.waitForService(PLATFORM_WAIT_MILLIS) == null) {
        throw new IllegalStateException("The Eclipse content type service did not start within "
            + PLATFORM_WAIT_MILLIS / 1000 + " s (is Felix SCR in the run bundles?)");
      }
      server = new McpServerApplication();
      server.start(null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      exitCode = 1;
    } catch (Throwable e) {
      e.printStackTrace(); // stdout carries the protocol; McpServerApplication sent System.out to stderr
      exitCode = 1;
    }
    System.exit(exitCode);
  }

  @Override
  public void stop(BundleContext context) {
    McpServerApplication current = server;
    if (current != null) {
      current.stop();
    }
  }
}
