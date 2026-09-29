using System;
using System.Collections.Generic;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace api.Migrations
{
    /// <inheritdoc />
    public partial class InitialCreate : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "cbo_collections",
                columns: table => new
                {
                    id = table.Column<string>(type: "text", nullable: false),
                    cbo_id = table.Column<string>(type: "text", nullable: false),
                    arrival_time = table.Column<string>(type: "text", nullable: false),
                    departure_time = table.Column<string>(type: "text", nullable: true),
                    donor_name = table.Column<string>(type: "text", nullable: false),
                    donor_signed = table.Column<bool>(type: "boolean", nullable: false),
                    cbo_signed = table.Column<bool>(type: "boolean", nullable: false),
                    delivery_note = table.Column<string>(type: "text", nullable: false),
                    note_attached = table.Column<bool>(type: "boolean", nullable: false),
                    collect_notes = table.Column<string>(type: "text", nullable: false),
                    shots = table.Column<List<bool>>(type: "boolean[]", nullable: false),
                    latitude = table.Column<double>(type: "double precision", nullable: true),
                    longitude = table.Column<double>(type: "double precision", nullable: true),
                    submitted_by = table.Column<string>(type: "text", nullable: true),
                    sync_status = table.Column<string>(type: "text", nullable: false),
                    created_at = table.Column<long>(type: "bigint", nullable: false),
                    updated_at = table.Column<long>(type: "bigint", nullable: false),
                    received_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false, defaultValueSql: "now()"),
                    sync_attempts = table.Column<int>(type: "integer", nullable: false),
                    last_sync_attempt_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    sync_error = table.Column<string>(type: "text", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_cbo_collections", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "cbos",
                columns: table => new
                {
                    id = table.Column<string>(type: "text", nullable: false),
                    name = table.Column<string>(type: "text", nullable: false),
                    status = table.Column<string>(type: "text", nullable: false),
                    meta = table.Column<string>(type: "text", nullable: false),
                    tone = table.Column<string>(type: "text", nullable: false),
                    sync_status = table.Column<string>(type: "text", nullable: false),
                    created_at = table.Column<long>(type: "bigint", nullable: false),
                    updated_at = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_cbos", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "foodspace_beneficiary_records",
                columns: table => new
                {
                    id = table.Column<string>(type: "text", nullable: false),
                    legal_name = table.Column<string>(type: "text", nullable: false),
                    contact_name = table.Column<string>(type: "text", nullable: false),
                    contact_email = table.Column<string>(type: "text", nullable: false),
                    contact_phone = table.Column<string>(type: "text", nullable: false),
                    website = table.Column<string>(type: "text", nullable: true),
                    address = table.Column<string>(type: "text", nullable: true),
                    address2 = table.Column<string>(type: "text", nullable: true),
                    province = table.Column<string>(type: "text", nullable: false),
                    what3words = table.Column<string>(type: "text", nullable: false),
                    core_business = table.Column<string>(type: "text", nullable: false),
                    target_population = table.Column<List<string>>(type: "text[]", nullable: false),
                    programmes = table.Column<string>(type: "text", nullable: false),
                    distribution_channel = table.Column<string>(type: "text", nullable: false),
                    full_time_females = table.Column<int>(type: "integer", nullable: false),
                    full_time_males = table.Column<int>(type: "integer", nullable: false),
                    volunteers = table.Column<int>(type: "integer", nullable: false),
                    registered_npo = table.Column<bool>(type: "boolean", nullable: false),
                    npo_certificate = table.Column<string>(type: "text", nullable: true),
                    registered_dsd = table.Column<bool>(type: "boolean", nullable: false),
                    pbo_certificate = table.Column<string>(type: "text", nullable: true),
                    race = table.Column<List<string>>(type: "text[]", nullable: false),
                    gender = table.Column<List<string>>(type: "text[]", nullable: false),
                    age_groups = table.Column<List<string>>(type: "text[]", nullable: false),
                    feeding_frequency = table.Column<string>(type: "text", nullable: false),
                    total_served = table.Column<int>(type: "integer", nullable: false),
                    females_served = table.Column<int>(type: "integer", nullable: false),
                    males_served = table.Column<int>(type: "integer", nullable: false),
                    african_served = table.Column<int>(type: "integer", nullable: false),
                    coloured_served = table.Column<int>(type: "integer", nullable: false),
                    indian_served = table.Column<int>(type: "integer", nullable: false),
                    white_served = table.Column<int>(type: "integer", nullable: false),
                    reliance_on_sa_harvest = table.Column<string>(type: "text", nullable: false),
                    transport_capacity = table.Column<string>(type: "text", nullable: false),
                    meals_provided = table.Column<List<string>>(type: "text[]", nullable: false),
                    days_of_week = table.Column<List<string>>(type: "text[]", nullable: false),
                    last_date_fed = table.Column<long>(type: "bigint", nullable: true),
                    food_storage = table.Column<List<string>>(type: "text[]", nullable: false),
                    kitchen_images = table.Column<string>(type: "text", nullable: true),
                    kitchen_cleanliness = table.Column<bool>(type: "boolean", nullable: false),
                    access_to_water = table.Column<bool>(type: "boolean", nullable: false),
                    toilets = table.Column<bool>(type: "boolean", nullable: false),
                    pest_free = table.Column<bool>(type: "boolean", nullable: false),
                    infrastructure_checks = table.Column<List<string>>(type: "text[]", nullable: false),
                    ease_of_access = table.Column<bool>(type: "boolean", nullable: false),
                    parking_security = table.Column<bool>(type: "boolean", nullable: false),
                    police_proximity = table.Column<string>(type: "text", nullable: true),
                    additional_comments = table.Column<string>(type: "text", nullable: true),
                    proposal_writing = table.Column<string>(type: "text", nullable: true),
                    digital_capabilities = table.Column<string>(type: "text", nullable: true),
                    facility_photos = table.Column<string>(type: "text", nullable: true),
                    has_sla = table.Column<bool>(type: "boolean", nullable: false),
                    has_consent = table.Column<bool>(type: "boolean", nullable: false),
                    has_policy = table.Column<bool>(type: "boolean", nullable: false),
                    certificates = table.Column<string>(type: "text", nullable: true),
                    fetched_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false, defaultValueSql: "now()")
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_foodspace_beneficiary_records", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "vetting_decisions",
                columns: table => new
                {
                    id = table.Column<string>(type: "text", nullable: false),
                    foodspace_record_id = table.Column<string>(type: "text", nullable: false),
                    outcome = table.Column<string>(type: "text", nullable: false),
                    notes = table.Column<string>(type: "text", nullable: true),
                    officer_id = table.Column<string>(type: "text", nullable: false),
                    decision_timestamp = table.Column<long>(type: "bigint", nullable: false),
                    sync_status = table.Column<string>(type: "text", nullable: false),
                    created_at = table.Column<long>(type: "bigint", nullable: false),
                    updated_at = table.Column<long>(type: "bigint", nullable: false),
                    received_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false, defaultValueSql: "now()"),
                    sync_attempts = table.Column<int>(type: "integer", nullable: false),
                    last_sync_attempt_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    sync_error = table.Column<string>(type: "text", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_vetting_decisions", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "product_lines",
                columns: table => new
                {
                    id = table.Column<string>(type: "text", nullable: false),
                    collection_id = table.Column<string>(type: "text", nullable: false),
                    category = table.Column<string>(type: "text", nullable: false),
                    kg = table.Column<string>(type: "text", nullable: false),
                    notes = table.Column<string>(type: "text", nullable: true),
                    sync_status = table.Column<string>(type: "text", nullable: false),
                    created_at = table.Column<long>(type: "bigint", nullable: false),
                    updated_at = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_product_lines", x => x.id);
                    table.ForeignKey(
                        name: "fk_product_lines_cbo_collections_collection_id",
                        column: x => x.collection_id,
                        principalTable: "cbo_collections",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_cbo_collections_cbo_id",
                table: "cbo_collections",
                column: "cbo_id");

            migrationBuilder.CreateIndex(
                name: "ix_cbo_collections_sync_status",
                table: "cbo_collections",
                column: "sync_status");

            migrationBuilder.CreateIndex(
                name: "ix_product_lines_collection_id",
                table: "product_lines",
                column: "collection_id");

            migrationBuilder.CreateIndex(
                name: "ix_vetting_decisions_foodspace_record_id",
                table: "vetting_decisions",
                column: "foodspace_record_id");

            migrationBuilder.CreateIndex(
                name: "ix_vetting_decisions_officer_id",
                table: "vetting_decisions",
                column: "officer_id");

            migrationBuilder.CreateIndex(
                name: "ix_vetting_decisions_sync_status",
                table: "vetting_decisions",
                column: "sync_status");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "cbos");

            migrationBuilder.DropTable(
                name: "foodspace_beneficiary_records");

            migrationBuilder.DropTable(
                name: "product_lines");

            migrationBuilder.DropTable(
                name: "vetting_decisions");

            migrationBuilder.DropTable(
                name: "cbo_collections");
        }
    }
}
