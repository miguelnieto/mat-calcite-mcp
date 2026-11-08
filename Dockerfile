# MCP server image. Released images: ghcr.io/miguelnieto/mat-calcite-mcp:<version> (amd64, arm64).
# To build one yourself, build the jar first (./gradlew :server:export), then
#   docker build -t mat-calcite-mcp .
#
# Run it with -i and no TTY: the server speaks MCP on stdin/stdout.
#   docker run -i --rm --network none --user "$(id -u):$(id -g)" -v /path/to/dumps:/dumps ghcr.io/miguelnieto/mat-calcite-mcp
# The server needs no network; the README has the locked-down variant (read-only filesystem, no capabilities...).
# Dumps are opened as /dumps/<file>.hprof. MAT writes its index files next to them, so the volume must be
# writable by the user the container runs as: 1000 by default, or the one given with --user.
FROM eclipse-temurin:17-jre

COPY server/build/distributions/executable/mat-calcite-mcp.jar /opt/mat-calcite-mcp/mat-calcite-mcp.jar

# The heap follows the container's memory limit (docker run --memory 8g ...); MAT needs about as much as the
# dump's size. JAVA_TOOL_OPTIONS can override it, e.g. -e JAVA_TOOL_OPTIONS=-Xmx4g. MAT's preferences and logs go
# to /tmp, writable by any user.
VOLUME ["/dumps"]

# Not root. 1000:1000 is the first user of most Linux systems (and the base image's "ubuntu"), so a dumps folder of
# yours is writable out of the box; --user overrides it. Nothing here needs more than that.
USER 1000:1000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dosgi.instance.area=/tmp/mat-calcite-mcp/workspace", "-Dosgi.configuration.area=/tmp/mat-calcite-mcp/configuration", "-jar", "/opt/mat-calcite-mcp/mat-calcite-mcp.jar"]
