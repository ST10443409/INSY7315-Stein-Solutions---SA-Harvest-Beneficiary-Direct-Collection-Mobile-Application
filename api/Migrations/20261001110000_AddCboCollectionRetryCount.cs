using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace api.Migrations
{
    /// <summary>
    /// Mirrors the Room field cbo_collections.retryCount (added for the device sync worker). Written by hand;
    /// AppDbContextModelSnapshot already reflects it.
    /// </summary>
    [DbContext(typeof(api.Data.AppDbContext))]
    [Migration("20261001110000_AddCboCollectionRetryCount")]
    public partial class AddCboCollectionRetryCount : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<int>(
                name: "retry_count",
                table: "cbo_collections",
                type: "integer",
                nullable: false,
                defaultValue: 0);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(name: "retry_count", table: "cbo_collections");
        }
    }
}
