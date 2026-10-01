using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace api.Migrations
{
    /// <summary>
    /// Duplicate detection for CBO collections (#38): a fingerprint of the real-world collection, a pointer from a
    /// suspected duplicate to its original, and a partial unique index so only one original exists per fingerprint.
    /// Written by hand (no Designer file); AppDbContextModelSnapshot already reflects it.
    /// </summary>
    [DbContext(typeof(api.Data.AppDbContext))]
    [Migration("20261001100000_AddCboCollectionDuplicateDetection")]
    public partial class AddCboCollectionDuplicateDetection : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(name: "duplicate_key", table: "cbo_collections", type: "text", nullable: true);
            migrationBuilder.AddColumn<string>(name: "duplicate_of_id", table: "cbo_collections", type: "text", nullable: true);

            migrationBuilder.CreateIndex(
                name: "ux_cbo_collections_duplicate_key_original",
                table: "cbo_collections",
                column: "duplicate_key",
                unique: true,
                filter: "duplicate_of_id IS NULL");

            migrationBuilder.CreateIndex(
                name: "ix_cbo_collections_duplicate_of_id",
                table: "cbo_collections",
                column: "duplicate_of_id");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(name: "ux_cbo_collections_duplicate_key_original", table: "cbo_collections");
            migrationBuilder.DropIndex(name: "ix_cbo_collections_duplicate_of_id", table: "cbo_collections");
            migrationBuilder.DropColumn(name: "duplicate_key", table: "cbo_collections");
            migrationBuilder.DropColumn(name: "duplicate_of_id", table: "cbo_collections");
        }
    }
}
