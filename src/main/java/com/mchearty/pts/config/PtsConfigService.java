package com.mchearty.pts.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads and exposes the PTS configuration from {@code pts-common.toml}.
 *
 * <p>Configuration controls which blocks receive slabs (via keywords, namespaces,
 * or exact IDs), edge-conversion probability, and corner generation behavior.
 */
public class PtsConfigService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PtsConfigService.class);
  private static final String CONFIG_FILE_NAME = "pts-common.toml";

  /** Exact block IDs that must always receive a PTS slab. */
  public static final Set<ResourceLocation> EXACT_TARGETS = new HashSet<>();

  /** Namespaces whose blocks are automatically eligible for slabs. */
  public static final Set<String> TARGET_NAMESPACES = new HashSet<>();

  /** Regex that matches terrain-related keywords in block paths. */
  public static Pattern KEYWORD_PATTERN = Pattern.compile("");

  /** Percentage of eligible edges that will be converted to slabs (0-100). */
  public static int EDGE_CONVERSION_PERCENT = 95;

  /** Whether corner smoothing should be enabled. */
  public static boolean GENERATE_CORNERS = true;

  /**
   * Loads (and creates if missing) the common configuration file and populates
   * all static configuration fields.
   */
  public static void loadConfig() {
    File configFile = getConfigPath().toFile();
    EXACT_TARGETS.clear();
    TARGET_NAMESPACES.clear();

    try (CommentedFileConfig configData = CommentedFileConfig.builder(configFile)
        .sync()
        .autosave()
        .writingMode(WritingMode.REPLACE)
        .build()) {

      configData.load();
      boolean needsSave = false;

      if (!configData.contains("edge_conversion_percent")) {
        configData.set("edge_conversion_percent", 95);
        needsSave = true;
      }

      if (!configData.contains("generate_corners")) {
        configData.set("generate_corners", true);
        needsSave = true;
      }

      if (!configData.contains("keywords")) {
        configData.set("keywords", List.of(
            "dirt", "grass", "sand", "sandstone", "terracotta", "stone", "gravel", "mud",
            "podzol", "mycelium", "deepslate", "netherrack", "basalt", "blackstone", "soul",
            "nylium", "wart", "end_stone", "ice", "prismarine", "moss", "ash", "peat",
            "silt", "chalk", "argillite", "travertine", "chert", "kaolin", "prismoss", "salt"
        ));
        needsSave = true;
      }

      if (!configData.contains("namespaces")) {
        configData.set("namespaces", List.of());
        needsSave = true;
      }

      if (!configData.contains("exact_blocks")) {
        configData.set("exact_blocks", List.of());
        needsSave = true;
      }

      if (needsSave) {
        configData.save();
      }

      EDGE_CONVERSION_PERCENT = Math.clamp(configData.getIntOrElse("edge_conversion_percent", 95), 0, 100);
      GENERATE_CORNERS = configData.getOrElse("generate_corners", true);

      List<String> keywords = configData.get("keywords");
      if (keywords != null && !keywords.isEmpty()) {
        String regexStr = "(^|[_.-])(" + keywords.stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")([_.-]|$)";
        KEYWORD_PATTERN = Pattern.compile(regexStr, Pattern.CASE_INSENSITIVE);
      }

      TARGET_NAMESPACES.addAll(configData.get("namespaces"));

      for (String id : configData.<List<String>>get("exact_blocks")) {
        try {
          EXACT_TARGETS.add(ResourceLocation.parse(id));
        } catch (Exception e) {
          LOGGER.error("PTS Config Error: Failed to parse block id {}", id, e);
        }
      }
    } catch (Exception e) {
      LOGGER.error("PTS Config Error: Failed to parse configuration", e);
    }
  }

  /**
   * Returns the path to the PTS configuration file inside the config directory.
   *
   * @return path to {@code pts-common.toml}
   */
  public static Path getConfigPath() {
    return FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE_NAME);
  }

  /**
   * Computes a deterministic hash of the current configuration for cache invalidation.
   *
   * @return hash string used to decide whether the runtime pack must be rebuilt
   */
  public static String calculateConfigHash(Set<ResourceLocation> targetIds) {
    return String.valueOf(targetIds.hashCode() ^ EXACT_TARGETS.hashCode() ^ TARGET_NAMESPACES.hashCode() ^ KEYWORD_PATTERN.pattern().hashCode());
  }
}