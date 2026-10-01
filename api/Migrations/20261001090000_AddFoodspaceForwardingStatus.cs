using System;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace api.Migrations
{
    /// <summary>
    /// Adds the server-only Foodspace forwarding columns to the two forwarded tables. Written by hand
    /// (no Designer file): if you regenerate migrations, `dotnet ef migrations add` will produce the
    /// equivalent, and AppDbContextModelSnapshot already reflects this change.
    /// </summary>
    [DbContext(typeof(api.Data.AppDbContext))]
    [Migration("20261001090000_AddFoodspaceForwardingStatus")]
    public partial class AddFoodspaceForwardingStatus : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            foreach (var table in new[] { "cbo_collections", "vetting_decisions" })
            {
                migrationBuilder.AddColumn<string>(
                    name: "forwarding_status",
                    table: table,
                    type: "text",
                    nullable: false,
                    defaultValue: "PENDING");

                migrationBuilder.AddColumn<DateTimeOffset>(
                    name: "next_forward_attempt_at",
                    table: table,
                    type: "timestamp with time zone",
                    nullable: true);
            }

            migrationBuilder.CreateIndex(
                name: "ix_cbo_collections_forwarding_status",
                table: "cbo_collections",
                column: "forwarding_status");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "ix_cbo_collections_forwarding_status",
                table: "cbo_collections");

            foreach (var table in new[] { "cbo_collections", "vetting_decisions" })
            {
                migrationBuilder.DropColumn(name: "forwarding_status", table: table);
                migrationBuilder.DropColumn(name: "next_forward_attempt_at", table: table);
            }
        }
    }
}
