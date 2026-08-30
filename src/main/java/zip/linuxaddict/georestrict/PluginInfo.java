package zip.linuxaddict.georestrict;

public final class PluginInfo {
    public static final String VERSION = "2.0.2";
    public static final String MODRINTH_PROJECT = "georestrict";
    public static final String USER_AGENT = "GeoRestrict/" + VERSION;
    public static final String DOCS_URL = "https://georestrict-docs.pages.dev/";
    public static final String SUPPORT_URL = "https://discord.com/invite/GYsTt96ypf";
    public static final String COMMUNITY_MESSAGE =
        "Demonz Development believes listening to its users comes before anything else.";
    public static final String FEEDBACK_MESSAGE =
        "Found a bug, have a report, or want to share feedback? Join our Discord: " + SUPPORT_URL
            + " - we would genuinely love to hear from you.";

    public static final int BSTATS_BUKKIT = 32871;
    public static final int BSTATS_BUNGEE = 32872;
    public static final int BSTATS_VELOCITY = 32873;

    private PluginInfo() {}
}