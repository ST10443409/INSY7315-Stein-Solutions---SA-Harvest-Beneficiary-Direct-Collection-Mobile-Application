FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
# Restore first so the package layer is cached until the .csproj changes.
COPY api.csproj .
RUN dotnet restore
COPY . .
RUN dotnet publish -c Release -o /app/publish --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
WORKDIR /app
COPY --from=build /app/publish .
# Listens on 8080 (ASPNETCORE_HTTP_PORTS default in .NET 8+ images); runs as the non-root 'app' user.
USER app
ENTRYPOINT ["dotnet", "api.dll"]
