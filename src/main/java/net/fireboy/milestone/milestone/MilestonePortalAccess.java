package net.fireboy.milestone.milestone;

/**
 * Portal access tiers, independent of any particular destination or portal block.
 * Vanilla Nether adapters are live; modded portal adapters can query the same
 * saved flags without adding one enum value for every dimension/mod.
 */
public enum MilestonePortalAccess {
    SHARED_GATEWAY("Shared gateway"),
    PUBLIC_GATEWAY_1("First public gateway"),
    PUBLIC_GATEWAY_2("Second public gateway"),
    PERSONAL_PORTALS("Personal portals");

    private final String label;

    MilestonePortalAccess(String label) { this.label = label; }
    public String label() { return label; }

    /** Existing Nether-portal mechanics still read their legacy flags. */
    public MilestoneUnlock legacyNetherFlag() {
        return switch (this) {
            case SHARED_GATEWAY -> MilestoneUnlock.SHARED_NETHER_GATEWAY;
            case PUBLIC_GATEWAY_1 -> MilestoneUnlock.NETHER_PUBLIC_GATEWAY_1;
            case PUBLIC_GATEWAY_2 -> MilestoneUnlock.NETHER_PUBLIC_GATEWAY_2;
            case PERSONAL_PORTALS -> MilestoneUnlock.NETHER_PERSONAL_PORTALS;
        };
    }
}
