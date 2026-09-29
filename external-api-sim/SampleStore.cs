using System.Collections.Concurrent;

namespace external_api_sim;

/// <summary>
/// In-memory stand-in for Foodspace's database: fictional seed data for the "pull" endpoints, and a record
/// of everything pushed to the "push" endpoints. Resets whenever the simulator restarts.
/// </summary>
public class SampleStore
{
    public List<CboDto> Cbos { get; } = new()
    {
        new("cbo-001", "Khayelitsha Community Kitchen", "Active", "Khayelitsha, Western Cape", "OK"),
        new("cbo-002", "Soweto Feeding Scheme", "Active", "Soweto, Gauteng", "OK"),
        new("cbo-003", "Umlazi Hope Centre", "Pending review", "Umlazi, KwaZulu-Natal", "NEW"),
    };

    public List<BeneficiaryDto> Beneficiaries { get; } = CreateBeneficiaries();

    public ConcurrentDictionary<string, CboCollectionDto> ReceivedCollections { get; } = new();
    public ConcurrentDictionary<string, VettingDecisionDto> ReceivedDecisions { get; } = new();

    private static List<BeneficiaryDto> CreateBeneficiaries()
    {
        var baseline = new BeneficiaryDto
        {
            Id = "fs-1001",
            LegalName = "Sizanani Community Feeding NPO",
            ContactName = "Nomsa Dlamini",
            ContactEmail = "nomsa@sizanani.example.org",
            ContactPhone = "+27 82 000 0001",
            Website = "https://sizanani.example.org",
            Address = "12 Vilakazi Street",
            Address2 = "Orlando West",
            Province = "Gauteng",
            What3words = "filled.count.soap",
            CoreBusiness = "Daily hot meals for children and the elderly",
            TargetPopulation = new() { "Children", "Elderly" },
            Programmes = "Feeding scheme, after-school homework club",
            DistributionChannel = "On-site kitchen",
            FullTimeFemales = 3,
            FullTimeMales = 1,
            Volunteers = 12,
            RegisteredNpo = true,
            NpoCertificate = "NPO-123-456",
            RegisteredDsd = true,
            PboCertificate = "PBO-930-0001",
            Race = new() { "African" },
            Gender = new() { "Female", "Male" },
            AgeGroups = new() { "0-12", "60+" },
            FeedingFrequency = "Daily",
            TotalServed = 220,
            FemalesServed = 130,
            MalesServed = 90,
            AfricanServed = 220,
            RelianceOnSaHarvest = "High",
            TransportCapacity = "One bakkie",
            MealsProvided = new() { "Lunch", "Dinner" },
            DaysOfWeek = new() { "Mon", "Tue", "Wed", "Thu", "Fri" },
            LastDateFed = 1_790_000_000_000,
            FoodStorage = new() { "Fridge", "Dry store" },
            KitchenCleanliness = true,
            AccessToWater = true,
            Toilets = true,
            PestFree = true,
            InfrastructureChecks = new() { "Gas safety certificate" },
            EaseOfAccess = true,
            ParkingSecurity = true,
            PoliceProximity = "Orlando SAPS, 1.2 km",
            AdditionalComments = "Well organised; strong volunteer base.",
            HasSla = true,
            HasConsent = true,
            HasPolicy = true,
        };

        return new()
        {
            baseline,
            baseline with
            {
                Id = "fs-1002",
                LegalName = "Ubuntu Soup Kitchen",
                ContactName = "Thabo Mokoena",
                ContactEmail = "thabo@ubuntusoup.example.org",
                ContactPhone = "+27 82 000 0002",
                Website = null,
                Address = "4 Main Road",
                Address2 = null,
                Province = "Western Cape",
                What3words = "pilot.mango.trend",
                CoreBusiness = "Weekday soup and bread for the unemployed",
                TargetPopulation = new() { "Adults", "Unemployed" },
                FeedingFrequency = "Weekdays",
                TotalServed = 90,
                FemalesServed = 40,
                MalesServed = 50,
                AfricanServed = 60,
                ColouredServed = 30,
                RegisteredDsd = false,
                PboCertificate = null,
                PestFree = false,
                RelianceOnSaHarvest = "Medium",
                HasPolicy = false,
                AdditionalComments = "Pest control needed before approval.",
            },
            baseline with
            {
                Id = "fs-1003",
                LegalName = "Hope Rising Youth Centre",
                ContactName = "Anele Zulu",
                ContactEmail = "anele@hoperising.example.org",
                ContactPhone = "+27 82 000 0003",
                Address = "88 Chatsworth Drive",
                Address2 = null,
                Province = "KwaZulu-Natal",
                What3words = "table.lucky.river",
                CoreBusiness = "Youth programmes with a weekend meal",
                TargetPopulation = new() { "Youth" },
                FeedingFrequency = "Weekends",
                TotalServed = 60,
                FemalesServed = 35,
                MalesServed = 25,
                AfricanServed = 45,
                IndianServed = 15,
                RegisteredNpo = false,
                NpoCertificate = null,
                LastDateFed = null,
                RelianceOnSaHarvest = "Low",
                HasSla = false,
            },
        };
    }
}
