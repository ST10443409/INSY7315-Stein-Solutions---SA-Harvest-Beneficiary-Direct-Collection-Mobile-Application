using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace api.Migrations
{
    /// <summary>
    /// Mirrors the Room fields vetting_decisions.retryCount and .syncErrorCode (the sync bookkeeping the device's vetting
    /// sync worker keeps). Written by hand; AppDbContextModelSnapshot already reflects it.
    /// </summary>
    [DbContext(typeof(api.Data.AppDbContext))]
    [Migration("20261001140000_AddVettingDecisionDeviceBookkeeping")]
    public partial class AddVettingDecisionDeviceBookkeeping : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<int>(
                name: "retry_count",
                table: "vetting_decisions",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<string>(
                name: "sync_error_code",
                table: "vetting_decisions",
                type: "text",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(name: "retry_count", table: "vetting_decisions");
            migrationBuilder.DropColumn(name: "sync_error_code", table: "vetting_decisions");
        }
    }
}
