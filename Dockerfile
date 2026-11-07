# MCP server image: build the jar first (./gradlew :server:export), then
#   docker build -t mat-calcite-mcp .
#   docker run -i --rm -v /path/to/dumps:/dumps mat-calcite-mcp
# The server speaks MCP on stdin/stdout, so run it with -i and no TTY. Dumps are opened as /dumps/<file>.hprof;
# MAT writes its index files next to them, so the volume must be writable.
FROM eclipse-temurin:17-jre

COPY server/build/distributions/executable/mat-calcite-mcp.jar /opt/mat-calcite-mcp/mat-calcite-mcp.jar

# MAT's preferences and logs; heap size as for any JVM, e.g. -e JAVA_TOOL_OPTIONS=-Xmx4g
VOLUME ["/dumps"]
ENTRYPOINT ["java", "-Dosgi.instance.area=/tmp/mat-calcite-mcp/workspace", "-jar", "/opt/mat-calcite-mcp/mat-calcite-mcp.jar"]
