using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace api.Migrations
{
    /// <summary>
    /// Mirrors the Room field cbo_collections.syncErrorCode (the reason the device's last sync attempt for a record
    /// failed). Written by hand; AppDbContextModelSnapshot already reflects it.
    /// </summary>
    [DbContext(typeof(api.Data.AppDbContext))]
    [Migration("20261001120000_AddCboCollectionSyncErrorCode")]
    public partial class AddCboCollectionSyncErrorCode : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "sync_error_code",
                table: "cbo_collections",
                type: "text",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(name: "sync_error_code", table: "cbo_collections");
        }
    }
}
