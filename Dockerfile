# MCP server image. Released images: ghcr.io/miguelnieto/mat-calcite-mcp:<version> (amd64, arm64).
# To build one yourself, build the jar first (./gradlew :server:export), then
#   docker build -t mat-calcite-mcp .
#
# Run it with -i and no TTY: the server speaks MCP on stdin/stdout.
#   docker run -i --rm --user "$(id -u):$(id -g)" -v /path/to/dumps:/dumps ghcr.io/miguelnieto/mat-calcite-mcp
# Dumps are opened as /dumps/<file>.hprof. MAT writes its index files next to them, so the volume must be
# writable; --user makes those files yours rather than root's.
FROM eclipse-temurin:17-jre

COPY server/build/distributions/executable/mat-calcite-mcp.jar /opt/mat-calcite-mcp/mat-calcite-mcp.jar

# The heap follows the container's memory limit (docker run --memory 8g ...); MAT needs about as much as the
# dump's size. JAVA_TOOL_OPTIONS can override it, e.g. -e JAVA_TOOL_OPTIONS=-Xmx4g. MAT's preferences and logs go
# to /tmp, writable by any user.
VOLUME ["/dumps"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dosgi.instance.area=/tmp/mat-calcite-mcp/workspace", "-Dosgi.configuration.area=/tmp/mat-calcite-mcp/configuration", "-jar", "/opt/mat-calcite-mcp/mat-calcite-mcp.jar"]
