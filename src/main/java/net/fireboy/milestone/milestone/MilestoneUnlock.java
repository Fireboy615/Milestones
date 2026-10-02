package net.fireboy.milestone.milestone;

/**
 * Server-wide progression flags awarded by Milestone participation thresholds.
 * Other gameplay systems (portal rules, recipes, public gateways, etc.) should
 * query these flags instead of inferring unlocks from a particular Core.
 */
public enum MilestoneUnlock {
    SHARED_NETHER_GATEWAY,
    NETHER_PUBLIC_GATEWAY_1,
    NETHER_PUBLIC_GATEWAY_2,
    NETHER_PERSONAL_PORTALS
}
