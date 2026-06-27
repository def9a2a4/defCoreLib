package anon.def9a2a4.corelib;

/**
 * Banner upgrade tier, used to gate which banners a block (e.g. a windmill) will accept as
 * crafting ingredients. A {@code null} tier on a block means "no gating" (any banner).
 */
public enum BannerTier {
    NORMAL,
    LARGE,
    HUGE
}
