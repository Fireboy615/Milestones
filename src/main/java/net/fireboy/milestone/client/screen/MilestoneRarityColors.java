package net.fireboy.milestone.client.screen;
import java.util.Locale;
/** Identical to Iron's Spells 3.16.3 SpellRarity display ChatFormatting values. */
public final class MilestoneRarityColors {
    private MilestoneRarityColors(){}
    public static int color(String rarity) {
        if(rarity==null)return 0xFFAAAAAA;
        return switch(rarity.toLowerCase(Locale.ROOT)){
            case "uncommon" -> 0xFF55FF55;
            case "rare" -> 0xFF55FFFF;
            case "epic" -> 0xFFFF55FF;
            case "legendary" -> 0xFFFFAA00;
            default -> 0xFFAAAAAA;
        };
    }
    public static String label(String rarity) {
        if(rarity==null||rarity.isBlank())return "Common";
        String v=rarity.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(v.charAt(0))+v.substring(1);
    }
    public static net.minecraft.ChatFormatting style(String rarity){
        if(rarity==null)return net.minecraft.ChatFormatting.GRAY;
        return switch(rarity.toLowerCase(Locale.ROOT)){
            case "uncommon" -> net.minecraft.ChatFormatting.GREEN;
            case "rare" -> net.minecraft.ChatFormatting.AQUA;
            case "epic" -> net.minecraft.ChatFormatting.LIGHT_PURPLE;
            case "legendary" -> net.minecraft.ChatFormatting.GOLD;
            default -> net.minecraft.ChatFormatting.GRAY;
        };
    }
    public static net.minecraft.network.chat.Component styled(String rarity){
        return net.minecraft.network.chat.Component.literal(label(rarity))
                .withStyle(style(rarity));
    }
}
