using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace api.Migrations
{
    /// <summary>
    /// users.cbo_id: the CBO a CBO_COLLECTION user collects for. Server-only (users has no Room counterpart).
    /// Written by hand; AppDbContextModelSnapshot already reflects it.
    /// </summary>
    [DbContext(typeof(api.Data.AppDbContext))]
    [Migration("20261001130000_AddUserCboId")]
    public partial class AddUserCboId : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "cbo_id",
                table: "users",
                type: "text",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(name: "cbo_id", table: "users");
        }
    }
}
