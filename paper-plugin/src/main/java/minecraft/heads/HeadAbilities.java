package minecraft.heads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

/**
 * Passive and active power definitions for every mob head. Each profile is a
 * combination of refreshing potion effects; a few mobs also have dedicated
 * active abilities implemented in {@link HeadPowers}.
 */
public final class HeadAbilities {

    public record Effect(PotionEffectType type, int amplifier) {
    }

    public record Profile(List<Effect> effects, String description) {
    }

    private static final Map<String, Profile> PROFILES = new HashMap<>();

    private HeadAbilities() {
    }

    private static Effect effect(PotionEffectType type, int amplifier) {
        return new Effect(type, amplifier);
    }

    private static void add(String key, String description, Effect... effects) {
        PROFILES.put(key, new Profile(List.of(effects), description));
    }

    static {
        add("ALLAY", "Floats gently and moves quickly while helping.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.SLOW_FALLING, 0));
        add("ARMADILLO", "Rolls with the blows, gaining heavy damage resistance.",
                effect(PotionEffectType.RESISTANCE, 1),
                effect(PotionEffectType.ABSORPTION, 0));
        add("ARMOR_STAND", "A sturdy wooden pose grants a little luck and resilience.",
                effect(PotionEffectType.RESISTANCE, 0),
                effect(PotionEffectType.LUCK, 0));
        add("AXOLOTL", "Breathes underwater and regenerates health.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.REGENERATION, 0),
                effect(PotionEffectType.DOLPHINS_GRACE, 0));
        add("BAT", "Sees in the dark and never takes fall damage.",
                effect(PotionEffectType.NIGHT_VISION, 0),
                effect(PotionEffectType.SLOW_FALLING, 0));
        add("BEE", "Buzzes upward and lands softly.",
                effect(PotionEffectType.JUMP_BOOST, 1),
                effect(PotionEffectType.SLOW_FALLING, 0));
        add("BLAZE", "Burns brightly with fire resistance and strength.",
                effect(PotionEffectType.FIRE_RESISTANCE, 0),
                effect(PotionEffectType.STRENGTH, 0));
        add("BOGGED", "Marsh resilience repairs damage and toughens skin.",
                effect(PotionEffectType.RESISTANCE, 0),
                effect(PotionEffectType.REGENERATION, 0));
        add("BREEZE", "Rides the wind with speed, leaps and soft falls.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.JUMP_BOOST, 2),
                effect(PotionEffectType.SLOW_FALLING, 0));
        add("CAMEL", "Long legs carry you faster and higher.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.JUMP_BOOST, 1));
        add("CAMEL_HUSK", "Desert endurance grants speed and resistance.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.RESISTANCE, 0));
        add("CAT", "Land on your feet with feline speed and grace.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.SLOW_FALLING, 0));
        add("CAVE_SPIDER", "Climb through the dark with night vision and speed.",
                effect(PotionEffectType.NIGHT_VISION, 0),
                effect(PotionEffectType.SPEED, 1));
        add("CHICKEN", "Feathers slow every fall.",
                effect(PotionEffectType.SLOW_FALLING, 0));
        add("COD", "Swims freely beneath the waves.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.DOLPHINS_GRACE, 0));
        add("COPPER_GOLEM", "Tireless copper limbs grant haste and toughness.",
                effect(PotionEffectType.HASTE, 1),
                effect(PotionEffectType.RESISTANCE, 0));
        add("COW", "Stand on a grass block for 5 seconds to heal every 2 seconds.",
                effect(PotionEffectType.RESISTANCE, 0));
        add("CREAKING", "Creaking wood grants night vision and deep resistance.",
                effect(PotionEffectType.NIGHT_VISION, 0),
                effect(PotionEffectType.RESISTANCE, 1));
        add("CREEPER", "Right-click with an empty hand for a safe blast.",
                effect(PotionEffectType.SPEED, 0));
        add("DOLPHIN", "Glides through water with dolphins grace.",
                effect(PotionEffectType.DOLPHINS_GRACE, 1),
                effect(PotionEffectType.WATER_BREATHING, 0));
        add("DONKEY", "Carries you with speed and a strong jump.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.JUMP_BOOST, 1));
        add("DROWNED", "The deep grants water breathing, grace and strength.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.DOLPHINS_GRACE, 0),
                effect(PotionEffectType.STRENGTH, 0));
        add("ELDER_GUARDIAN", "Ancient power grants conduit strength and resistance.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.CONDUIT_POWER, 1),
                effect(PotionEffectType.RESISTANCE, 1));
        add("ENDER_DRAGON", "Dragon might grants huge strength, resistance and health.",
                effect(PotionEffectType.STRENGTH, 2),
                effect(PotionEffectType.RESISTANCE, 2),
                effect(PotionEffectType.REGENERATION, 1),
                effect(PotionEffectType.HEALTH_BOOST, 1));
        add("ENDERMAN", "Night vision and speed. Right-click to teleport.",
                effect(PotionEffectType.NIGHT_VISION, 0),
                effect(PotionEffectType.SPEED, 1));
        add("ENDERMITE", "Burrows quickly with haste and speed.",
                effect(PotionEffectType.HASTE, 1),
                effect(PotionEffectType.SPEED, 1));
        add("EVOKER", "Illager magic repairs wounds and toughens skin.",
                effect(PotionEffectType.RESISTANCE, 1),
                effect(PotionEffectType.REGENERATION, 0));
        add("FOX", "Quick and nimble with a springy leap.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.JUMP_BOOST, 1));
        add("FROG", "Leaps high and breathes underwater.",
                effect(PotionEffectType.JUMP_BOOST, 2),
                effect(PotionEffectType.WATER_BREATHING, 0));
        add("GHAST", "Fire resistance and a gentle float.",
                effect(PotionEffectType.FIRE_RESISTANCE, 0),
                effect(PotionEffectType.SLOW_FALLING, 0));
        add("GIANT", "Colossal strength, heavy resistance and extra hearts.",
                effect(PotionEffectType.STRENGTH, 1),
                effect(PotionEffectType.RESISTANCE, 1),
                effect(PotionEffectType.HEALTH_BOOST, 2));
        add("GLOW_SQUID", "Glows in the dark and breathes underwater.",
                effect(PotionEffectType.NIGHT_VISION, 0),
                effect(PotionEffectType.WATER_BREATHING, 0));
        add("GOAT", "Crazy leaps and mountain speed.",
                effect(PotionEffectType.JUMP_BOOST, 2),
                effect(PotionEffectType.SPEED, 1));
        add("GUARDIAN", "Sea guardian power grants water breathing and conduit power.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.CONDUIT_POWER, 0));
        add("HAPPY_GHAST", "Happy floating, regeneration and fire resistance.",
                effect(PotionEffectType.SLOW_FALLING, 0),
                effect(PotionEffectType.REGENERATION, 0),
                effect(PotionEffectType.FIRE_RESISTANCE, 0));
        add("HOGLIN", "Nether brute strength and speed.",
                effect(PotionEffectType.STRENGTH, 1),
                effect(PotionEffectType.SPEED, 0));
        add("HORSE", "Gallop with a strong speed boost.",
                effect(PotionEffectType.SPEED, 1));
        add("HUSK", "Desert dead gain strength and fire resistance.",
                effect(PotionEffectType.STRENGTH, 0),
                effect(PotionEffectType.FIRE_RESISTANCE, 0));
        add("ILLUSIONER", "Illager trickery grants speed and night vision.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.NIGHT_VISION, 0));
        add("IRON_GOLEM", "Golem strength with two extra hearts.",
                effect(PotionEffectType.STRENGTH, 1),
                effect(PotionEffectType.HEALTH_BOOST, 1));
        add("LLAMA", "A strong jump and a sturdy spit.",
                effect(PotionEffectType.JUMP_BOOST, 1),
                effect(PotionEffectType.STRENGTH, 0));
        add("MAGMA_CUBE", "Molten body grants fire resistance and bouncing.",
                effect(PotionEffectType.FIRE_RESISTANCE, 0),
                effect(PotionEffectType.JUMP_BOOST, 1));
        add("MANNEQUIN", "An uncanny stillness grants luck and resilience.",
                effect(PotionEffectType.LUCK, 0),
                effect(PotionEffectType.RESISTANCE, 0));
        add("MOOSHROOM", "Mushroom stew fills and heals.",
                effect(PotionEffectType.REGENERATION, 0),
                effect(PotionEffectType.SATURATION, 0));
        add("MULE", "Stubborn speed, jump and strength.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.JUMP_BOOST, 1),
                effect(PotionEffectType.STRENGTH, 0));
        add("NAUTILUS", "Master of the deep with breath and conduit power.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.BREATH_OF_THE_NAUTILUS, 0),
                effect(PotionEffectType.CONDUIT_POWER, 0));
        add("OCELOT", "Wild speed and a light step.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.JUMP_BOOST, 0));
        add("PANDA", "Bamboo bulk grants resistance and saturation.",
                effect(PotionEffectType.RESISTANCE, 1),
                effect(PotionEffectType.SATURATION, 0));
        add("PARCHED", "Sun-baked speed and fire resistance.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.FIRE_RESISTANCE, 0));
        add("PARROT", "Flutters softly and hops high.",
                effect(PotionEffectType.SLOW_FALLING, 0),
                effect(PotionEffectType.JUMP_BOOST, 1));
        add("PHANTOM", "Night flight grants night vision, speed and soft landings.",
                effect(PotionEffectType.NIGHT_VISION, 0),
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.SLOW_FALLING, 0));
        add("PIG", "Right-click to summon nearby pigs as guards.",
                effect(PotionEffectType.SPEED, 0));
        add("PIGLIN", "Nether speed and fire resistance.",
                effect(PotionEffectType.SPEED, 0),
                effect(PotionEffectType.FIRE_RESISTANCE, 0));
        add("PIGLIN_BRUTE", "Brute strength, resistance and fire resistance.",
                effect(PotionEffectType.STRENGTH, 1),
                effect(PotionEffectType.RESISTANCE, 0),
                effect(PotionEffectType.FIRE_RESISTANCE, 0));
        add("PILLAGER", "Raider speed and strength.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.STRENGTH, 0));
        add("POLAR_BEAR", "Arctic power grants strength and resistance.",
                effect(PotionEffectType.STRENGTH, 1),
                effect(PotionEffectType.RESISTANCE, 1));
        add("PUFFERFISH", "Spines and water breathing keep you safe.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.RESISTANCE, 1));
        add("RABBIT", "Incredible hops and quick feet.",
                effect(PotionEffectType.JUMP_BOOST, 3),
                effect(PotionEffectType.SPEED, 1));
        add("RAVAGER", "Ravager might grants strength, speed and extra hearts.",
                effect(PotionEffectType.STRENGTH, 2),
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.HEALTH_BOOST, 1));
        add("SALMON", "Strong currents grant grace and water breathing.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.DOLPHINS_GRACE, 1));
        add("SHEEP", "Wool softens falls and slowly repairs you.",
                effect(PotionEffectType.SLOW_FALLING, 0),
                effect(PotionEffectType.REGENERATION, 0));
        add("SHULKER", "Shell-like resistance and a gentle float.",
                effect(PotionEffectType.RESISTANCE, 2),
                effect(PotionEffectType.SLOW_FALLING, 0));
        add("SILVERFISH", "Burrowing haste and speed.",
                effect(PotionEffectType.HASTE, 2),
                effect(PotionEffectType.SPEED, 1));
        add("SKELETON", "Bone speed and perfect night vision.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.NIGHT_VISION, 0));
        add("SKELETON_HORSE", "Undead gallop with speed and a high jump.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.JUMP_BOOST, 1));
        add("SLIME", "Infinite jump boost while the head is worn.",
                effect(PotionEffectType.JUMP_BOOST, 4),
                effect(PotionEffectType.SLOW_FALLING, 0));
        add("SNIFFER", "Ancient resilience regenerates and resists damage.",
                effect(PotionEffectType.REGENERATION, 1),
                effect(PotionEffectType.RESISTANCE, 0),
                effect(PotionEffectType.SATURATION, 0));
        add("SNOW_GOLEM", "Snowy body softens every fall.",
                effect(PotionEffectType.SLOW_FALLING, 0),
                effect(PotionEffectType.RESISTANCE, 0));
        add("SPIDER", "Wall-walker senses with night vision, speed and leaps.",
                effect(PotionEffectType.NIGHT_VISION, 0),
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.JUMP_BOOST, 1));
        add("SQUID", "Sees and breathes in the deep.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.NIGHT_VISION, 0));
        add("STRAY", "Frozen arrows grant fire resistance, speed and resistance.",
                effect(PotionEffectType.FIRE_RESISTANCE, 0),
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.RESISTANCE, 0));
        add("STRIDER", "Lava walker with fire resistance and speed.",
                effect(PotionEffectType.FIRE_RESISTANCE, 1),
                effect(PotionEffectType.SPEED, 1));
        add("SULFUR_CUBE", "Bubbling heat grants fire resistance and bouncing.",
                effect(PotionEffectType.FIRE_RESISTANCE, 0),
                effect(PotionEffectType.JUMP_BOOST, 1));
        add("TADPOLE", "Tiny swimmer with water breathing and grace.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.DOLPHINS_GRACE, 0),
                effect(PotionEffectType.SPEED, 0));
        add("TRADER_LLAMA", "Travels far with jump, speed and luck.",
                effect(PotionEffectType.JUMP_BOOST, 1),
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.LUCK, 0));
        add("TROPICAL_FISH", "Colorful reef speed, grace and night vision.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.DOLPHINS_GRACE, 0),
                effect(PotionEffectType.NIGHT_VISION, 0));
        add("TURTLE", "Turtle shell grants water breathing and heavy resistance.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.RESISTANCE, 2));
        add("VEX", "Phases through the air with speed and soft landings.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.SLOW_FALLING, 0));
        add("VILLAGER", "Good trades grant luck and hero status.",
                effect(PotionEffectType.LUCK, 1),
                effect(PotionEffectType.HERO_OF_THE_VILLAGE, 0));
        add("VINDICATOR", "Axe-wielding strength and speed.",
                effect(PotionEffectType.STRENGTH, 1),
                effect(PotionEffectType.SPEED, 0));
        add("WANDERING_TRADER", "Far travels grant speed and exceptional luck.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.LUCK, 1));
        add("WARDEN", "Deep darkness grants immense strength and resistance.",
                effect(PotionEffectType.STRENGTH, 2),
                effect(PotionEffectType.RESISTANCE, 2),
                effect(PotionEffectType.NIGHT_VISION, 0));
        add("WITCH", "Brews constant regeneration and resistance.",
                effect(PotionEffectType.REGENERATION, 1),
                effect(PotionEffectType.RESISTANCE, 1));
        add("WITHER", "Withering power grants strength, resistance and regeneration.",
                effect(PotionEffectType.STRENGTH, 2),
                effect(PotionEffectType.RESISTANCE, 1),
                effect(PotionEffectType.REGENERATION, 1));
        add("WITHER_SKELETON", "Nether bones grant strength and fire resistance.",
                effect(PotionEffectType.STRENGTH, 1),
                effect(PotionEffectType.FIRE_RESISTANCE, 0));
        add("WOLF", "Pack hunter speed and strength.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.STRENGTH, 0));
        add("ZOGLIN", "Unstable strength and speed.",
                effect(PotionEffectType.STRENGTH, 1),
                effect(PotionEffectType.SPEED, 1));
        add("ZOMBIE", "Undead persistence grants strength and resistance.",
                effect(PotionEffectType.STRENGTH, 0),
                effect(PotionEffectType.RESISTANCE, 0));
        add("ZOMBIE_HORSE", "Undead gallop with speed, jump and water breathing.",
                effect(PotionEffectType.SPEED, 1),
                effect(PotionEffectType.JUMP_BOOST, 1),
                effect(PotionEffectType.WATER_BREATHING, 0));
        add("ZOMBIE_NAUTILUS", "Undead nautilus grants breath and resistance.",
                effect(PotionEffectType.WATER_BREATHING, 0),
                effect(PotionEffectType.BREATH_OF_THE_NAUTILUS, 0),
                effect(PotionEffectType.RESISTANCE, 0));
        add("ZOMBIE_VILLAGER", "A cursed trade grants regeneration, speed and luck.",
                effect(PotionEffectType.REGENERATION, 0),
                effect(PotionEffectType.SPEED, 0),
                effect(PotionEffectType.LUCK, 0));
        add("ZOMBIFIED_PIGLIN", "Nether gold grants fire resistance, speed and strength.",
                effect(PotionEffectType.FIRE_RESISTANCE, 0),
                effect(PotionEffectType.SPEED, 0),
                effect(PotionEffectType.STRENGTH, 0));
    }

    public static Profile profileFor(EntityType type) {
        if (type == null) {
            return null;
        }
        String key = type.getKey().getKey().toUpperCase(Locale.ROOT);
        Profile profile = PROFILES.get(key);
        if (profile != null) {
            return profile;
        }
        return switch (key) {
            case "MUSHROOM_COW" -> PROFILES.get("MOOSHROOM");
            case "SNOWMAN" -> PROFILES.get("SNOW_GOLEM");
            case "PIG_ZOMBIE" -> PROFILES.get("ZOMBIFIED_PIGLIN");
            default -> new Profile(List.of(effect(PotionEffectType.SPEED, 0)),
                    "A mysterious mob aura grants a small speed boost.");
        };
    }

    public static String description(EntityType type) {
        Profile profile = profileFor(type);
        return profile == null ? "No power" : profile.description();
    }

    public static int profileCount() {
        return PROFILES.size();
    }

    public static List<String> keys() {
        return new ArrayList<>(PROFILES.keySet());
    }
}
