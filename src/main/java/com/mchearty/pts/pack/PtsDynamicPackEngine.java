package com.mchearty.pts.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mchearty.pts.PtsMod;
import com.mchearty.pts.config.PtsConfigService;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runtime resource-pack engine that procedurally generates Minecraft assets for
 * smoothed-slab variants of target terrain blocks.
 *
 * <p>This class is the single source of truth for all dynamic assets created by the
 * Procedural Terrain Smoothing (PTS) mod. It reads original block models and blockstates
 * from any mod (including vanilla), slices the geometry at the 8/16 block height to
 * produce bottom/top/double slabs, adjusts UV coordinates on side faces, and emits
 * complete blockstate JSON, model JSON, loot tables, and block tags. Assets are written
 * into a temporary pack directory under the config folder and are regenerated only when
 * the PTS configuration hash changes.
 *
 * <p>All file-system and JSON operations are performed with JDK 21 {@code var} inference
 * to eliminate any dependency on internal NeoForge SPI packages that change between
 * loader builds. The engine is fully thread-safe for mod-initialization but performs
 * all heavy work on the main thread during world load.
 *
 * @see PtsConfigService#calculateConfigHash()
 * @see PtsMod
 */
public class PtsDynamicPackEngine {
  private static final Logger LOGGER = LoggerFactory.getLogger(PtsDynamicPackEngine.class);

  /** Root directory for the generated runtime resource pack. */
  public static final Path PACK_DIR = FMLPaths.CONFIGDIR.get().resolve("pts_generated_pack");

  /** File that stores the configuration hash to skip unnecessary rebuilds. */
  private static final Path HASH_FILE = PACK_DIR.resolve("config.hash");

  /**
   * In-memory mapping of block tags to the smoothed-slab variants that should be
   * included in them. Populated by {@link #addTagMapping(ResourceLocation, ResourceLocation)}
   * during block registration and flushed to JSON by {@link #generateDynamicTags()}.
   */
  private static final Map<ResourceLocation, Set<ResourceLocation>> PENDING_TAGS = new HashMap<>();

  /**
   * Registers a smoothed slab to be added to the given block tag at pack-generation time.
   *
   * @param tagLoc the resource location of the tag (e.g. {@code minecraft:slabs})
   * @param slabId the resource location of the generated slab block
   */
  public static void addTagMapping(ResourceLocation tagLoc, ResourceLocation slabId) {
    PENDING_TAGS.computeIfAbsent(tagLoc, k -> new HashSet<>()).add(slabId);
  }

  /**
   * Ensures the runtime pack directory exists. Idempotent and safe to call multiple times.
   */
  public static void initializePackDirectory() {
    try {
      if (!Files.exists(PACK_DIR)) Files.createDirectories(PACK_DIR);
    } catch (Exception ignored) {}
  }

  /**
   * Writes a string as UTF-8 JSON to the given path, overwriting any existing file.
   *
   * @param path the target file path
   * @param content the complete JSON string
   * @throws Exception if an I/O error occurs
   */
  private static void writeJson(Path path, String content) throws Exception {
    try (BufferedWriter writer = Files.newBufferedWriter(path)) {
      writer.write(content);
    }
  }

  /**
   * Reads a JSON asset (blockstate or model) from either a loaded mod file or the
   * mod's own classpath resources.
   *
   * <p>Uses {@code var} inference to avoid version-specific NeoForge internal imports.
   *
   * @param id the resource location of the asset
   * @param type either {@code "blockstates"} or {@code "models"}
   * @return the parsed {@link JsonObject} or {@code null} if the asset is missing
   */
  private static JsonObject readJson(ResourceLocation id, String type) {
    String path = "assets/" + id.getNamespace() + "/" + type + "/" + id.getPath() + ".json";

    // Extracted via `var` to bypass moving internal SPI interface package paths across versions
    var info = ModList.get().getModFileById(id.getNamespace());
    if (info != null) {
      Path resPath = info.getFile().findResource(path.split("/"));
      if (Files.exists(resPath)) {
        try (Reader reader = Files.newBufferedReader(resPath)) {
          return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception ignored) {}
      }
    }

    InputStream is = PtsMod.class.getResourceAsStream("/" + path);
    if (is != null) {
      try (Reader reader = new InputStreamReader(is)) {
        return JsonParser.parseReader(reader).getAsJsonObject();
      } catch (Exception ignored) {}
    }
    return null;
  }

  /**
   * Generates or updates the entire runtime resource pack if the PTS configuration
   * has changed since the last run.
   *
   * <p>This is the main entry point called from mod initialization after all target
   * blocks have been registered.
   *
   * @param targetIds the set of original block IDs for which smoothed slabs should be generated
   */
  public static void generateOrUpdateRuntimePack(Set<ResourceLocation> targetIds) {
    try {
      String currentHash = PtsConfigService.calculateConfigHash(targetIds);

      if (Files.exists(PACK_DIR) && Files.exists(HASH_FILE)) {
        String savedHash = Files.readString(HASH_FILE);
        if (currentHash.equals(savedHash)) {
          LOGGER.info("PTS: Configuration unchanged. Bypassing dynamic pack generation.");
          return;
        }
      }

      rebuildPackDirectory();
      writeJson(HASH_FILE, currentHash);

      int packFormat = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
      writeJson(PACK_DIR.resolve("pack.mcmeta"), """
          { "pack": { "pack_format": %d, "description": "PTS Dynamic Terrain Assets" } }
          """.formatted(packFormat));

      int count = 0;

      for (ResourceLocation targetId : targetIds) {
        String slabName = targetId.getNamespace() + "_" + targetId.getPath() + "_smoothed_slab";
        try {
          transformBlockstate(targetId, slabName);
          generateLootTable(targetId, slabName);
          count++;
        } catch (Exception e) {
          LOGGER.error("PTS: Failed to process block data for {}", targetId, e);
        }
      }

      generateDynamicTags();
      LOGGER.info("PTS: Successfully built runtime assets for {} targets.", count);

    } catch (Exception e) {
      LOGGER.error("PTS: Failed to generate runtime pack!", e);
    }
  }

  /**
   * Deletes and recreates the pack directory, ensuring the new pack is completely clean.
   *
   * <p>Contains a safety guard that prevents accidental deletion of directories outside
   * the Minecraft config folder.
   *
   * @throws Exception if an I/O error occurs or the safety check fails
   */
  private static void rebuildPackDirectory() throws Exception {
    if (!PACK_DIR.normalize().startsWith(FMLPaths.CONFIGDIR.get().normalize())) {
      throw new IllegalStateException("PTS: Unsafe pack directory path detected. Aborting deletion.");
    }
    if (Files.exists(PACK_DIR)) {
      try (var walk = Files.walk(PACK_DIR)) {
        walk.sorted(Comparator.reverseOrder()).forEach(path -> {
          if (!path.equals(PACK_DIR)) {
            try { Files.delete(path); } catch (Exception ignored) {}
          }
        });
      }
    }
    Files.createDirectories(PACK_DIR);
  }

  /**
   * Transforms the original blockstate JSON of a target block into a complete blockstate
   * definition that includes waterlogged variants of bottom/top/double slabs.
   *
   * @param targetId original block identifier
   * @param slabName generated slab block name (without namespace)
   * @throws Exception if JSON writing fails
   */
  private static void transformBlockstate(ResourceLocation targetId, String slabName) throws Exception {
    JsonObject originalState = readJson(targetId, "blockstates");
    if (originalState == null) return;

    boolean isTinted = targetId.getPath().contains("grass");
    boolean isFringe = isTinted || targetId.getPath().contains("snow");

    JsonObject newState = new JsonObject();
    if (originalState.has("variants")) {
      JsonObject newVariants = new JsonObject();
      JsonObject variants = originalState.getAsJsonObject("variants");

      String[] types = {"bottom", "top", "double"};
      String[] bools = {"false", "true"};

      for (String key : variants.keySet()) {
        JsonElement variantData = variants.get(key);
        
        Map<String, String> props = new HashMap<>();
        if (!key.isEmpty() && !key.equals("normal")) {
          for (String part : key.split(",")) {
            String[] kv = part.split("=");
            if (kv.length == 2) props.put(kv[0], kv[1]);
          }
        }
        
        // Remove strictly controlled bindings to map purely Cartesian logic natively
        props.remove("type");
        props.remove("waterlogged");

        for (String type : types) {
          JsonElement newVariantData = transformVariantData(variantData, targetId, type, isTinted, isFringe);
          
          for (String wl : bools) {
            Map<String, String> merged = new HashMap<>(props);
            merged.put("type", type);
            merged.put("waterlogged", wl);
            
            String newKey = merged.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
                
            newVariants.add(newKey, newVariantData);
          }
        }
      }
      newState.add("variants", newVariants);
      
    } else if (originalState.has("multipart")) {
      JsonArray newMultipart = new JsonArray();
      JsonArray multipart = originalState.getAsJsonArray("multipart");

      for (JsonElement partElem : multipart) {
        JsonObject part = partElem.getAsJsonObject();
        for (String type : new String[]{"bottom", "top", "double"}) {
          JsonObject newPart = part.deepCopy();
          JsonObject newWhen = new JsonObject();
          JsonArray andArray = new JsonArray();
          
          JsonObject typeCond = new JsonObject();
          typeCond.addProperty("type", type);
          andArray.add(typeCond);
          
          if (part.has("when")) {
            andArray.add(part.getAsJsonObject("when"));
          }
          newWhen.add("AND", andArray);
          newPart.add("when", newWhen);
          newPart.add("apply", transformVariantData(part.get("apply"), targetId, type, isTinted, isFringe));
          newMultipart.add(newPart);
        }
      }
      newState.add("multipart", newMultipart);
    }

    Path statesDir = PACK_DIR.resolve("assets/" + PtsMod.MODID + "/blockstates");
    Files.createDirectories(statesDir);
    writeJson(statesDir.resolve(slabName + ".json"), newState.toString());
  }

  /**
   * Transforms a single variant (or array of variants) by replacing its model reference
   * with a newly sliced PTS model.
   *
   * @param data the original variant JSON element
   * @param targetId the original block being sliced
   * @param type slab type ("bottom", "top", or "double")
   * @return transformed variant element
   * @throws Exception if model slicing or writing fails
   */
  private static JsonElement transformVariantData(JsonElement data, ResourceLocation targetId, String type, boolean isTinted, boolean isFringe) throws Exception {
    if (data.isJsonArray()) {
      JsonArray arr = new JsonArray();
      for (JsonElement el : data.getAsJsonArray()) {
        arr.add(transformSingleVariant(el.getAsJsonObject(), targetId, type, isTinted, isFringe));
      }
      return arr;
    } else {
      return transformSingleVariant(data.getAsJsonObject(), targetId, type, isTinted, isFringe);
    }
  }

  /**
   * Processes a single variant object: resolves its model, slices it, writes the new model
   * to the runtime pack, and updates the model reference.
   *
   * @param variant the original variant JSON
   * @param targetId original block ID
   * @param type slab type
   * @return updated variant JSON
   * @throws Exception if model operations fail
   */
  private static JsonObject transformSingleVariant(JsonObject variant, ResourceLocation targetId, String type, boolean isTinted, boolean isFringe) throws Exception {
    JsonObject newVariant = variant.deepCopy();
    String modelStr = variant.get("model").getAsString();
    ResourceLocation modelId = ResourceLocation.parse(modelStr.contains(":") ? modelStr : "minecraft:" + modelStr);

    String newModelName = targetId.getNamespace() + "_" + modelId.getPath().replace("/", "_") + "_" + type;

    JsonObject slicedModel = resolveAndSliceModel(modelId, type, isTinted, isFringe);
    if (slicedModel != null) {
      Path modelsDir = PACK_DIR.resolve("assets/" + PtsMod.MODID + "/models/block");
      Files.createDirectories(modelsDir);
      writeJson(modelsDir.resolve(newModelName + ".json"), slicedModel.toString());
    }

    newVariant.addProperty("model", PtsMod.MODID + ":block/" + newModelName);

    if (!type.equals("double")) {
      if (newVariant.has("x")) newVariant.remove("x");
      if (newVariant.has("z")) newVariant.remove("z");
    }

    return newVariant;
  }

  /**
   * Loads a model (following parent hierarchy) and slices its elements for the requested
   * slab type, also adjusting side-face UVs so the texture appears continuous across the cut.
   *
   * @param baseModelId the original model to slice
   * @param type slab type
   * @return the complete sliced model JSON, or {@code null} if the model could not be loaded
   */
  private static JsonObject resolveAndSliceModel(ResourceLocation baseModelId, String type, boolean isTinted, boolean isFringe) {
    JsonObject flattened = flattenModel(baseModelId);
    if (flattened == null) return null;

    JsonObject sliced = flattened.deepCopy();
    
    // Explicitly add cutout layer bounds globally to correctly solve Z-fighting on identically positioned side-texture overlays natively.
    if (!sliced.has("render_type")) {
      sliced.addProperty("render_type", "minecraft:cutout_mipped");
    }

    if (!sliced.has("elements")) return sliced;

    JsonArray elements = sliced.getAsJsonArray("elements");
    JsonArray newElements = new JsonArray();

    for (JsonElement elem : elements) {
      JsonObject el = elem.getAsJsonObject();
      JsonArray from = el.getAsJsonArray("from");
      JsonArray to = el.getAsJsonArray("to");

      float origMinY = from.get(1).getAsFloat();
      float origMaxY = to.get(1).getAsFloat();

      float minY = origMinY;
      float maxY = origMaxY;

      if (type.equals("bottom")) {
        maxY = Math.min(maxY, 8.0f);
      } else if (type.equals("top")) {
        minY = Math.max(minY, 8.0f);
      }

      if (minY >= maxY) continue;

      JsonArray newFrom = new JsonArray();
      newFrom.add(from.get(0).getAsFloat());
      newFrom.add(minY);
      newFrom.add(from.get(2).getAsFloat());

      JsonArray newTo = new JsonArray();
      newTo.add(to.get(0).getAsFloat());
      newTo.add(maxY);
      newTo.add(to.get(2).getAsFloat());

      el.add("from", newFrom);
      el.add("to", newTo);

      if (el.has("faces")) {
        JsonObject faces = el.getAsJsonObject("faces");
        for (String faceName : faces.keySet()) {
          JsonObject face = faces.getAsJsonObject(faceName);
          boolean isOverlay = face.has("texture") && face.get("texture").getAsString().contains("overlay");
          
          if (isTinted) {
            if (faceName.equals("up") || isOverlay) {
              face.addProperty("tintindex", 0);
            }
          }

          if (face.has("cullface")) {
            String cull = face.get("cullface").getAsString();
            boolean remove = false;
            if (cull.equals("down") && minY > 0.001f) remove = true;
            if (cull.equals("up") && maxY < 15.999f) remove = true;
            if (cull.equals("north") && newFrom.get(2).getAsFloat() > 0.001f) remove = true;
            if (cull.equals("south") && newTo.get(2).getAsFloat() < 15.999f) remove = true;
            if (cull.equals("west") && newFrom.get(0).getAsFloat() > 0.001f) remove = true;
            if (cull.equals("east") && newTo.get(0).getAsFloat() < 15.999f) remove = true;
            
            if (remove) face.remove("cullface");
          }

          if (faceName.equals("north") || faceName.equals("south") || faceName.equals("east") || faceName.equals("west")) {
            boolean hasUv = face.has("uv");
            if (hasUv || isFringe) {
              float u1 = 0, v1 = 0, u2 = 16, v2 = 16;
              if (hasUv) {
                JsonArray uv = face.getAsJsonArray("uv");
                u1 = uv.get(0).getAsFloat();
                v1 = uv.get(1).getAsFloat();
                u2 = uv.get(2).getAsFloat();
                v2 = uv.get(3).getAsFloat();
              } else {
                v1 = 16.0f - origMaxY;
                v2 = 16.0f - origMinY;
              }

              float vHeight = v2 - v1;
              float newV1, newV2;

              if (isFringe) {
                newV1 = v1;
                newV2 = v1 + vHeight * ((maxY - minY) / (origMaxY - origMinY));
              } else {
                newV1 = v1 + vHeight * ((origMaxY - maxY) / (origMaxY - origMinY));
                newV2 = v2 - vHeight * ((minY - origMinY) / (origMaxY - origMinY));
              }

              JsonArray newUv = new JsonArray();
              newUv.add(u1 == (int)u1 ? (int)u1 : u1);
              newUv.add(newV1 == (int)newV1 ? (int)newV1 : newV1);
              newUv.add(u2 == (int)u2 ? (int)u2 : u2);
              newUv.add(newV2 == (int)newV2 ? (int)newV2 : newV2);
              face.add("uv", newUv);
            }
          }
        }
      }
      newElements.add(el);
    }
    sliced.add("elements", newElements);
    return sliced;
  }

  /**
   * Flattens a model hierarchy by walking the {@code parent} chain and merging
   * textures and elements. Stops at {@code minecraft:block/block} or a {@code builtin/}
   * parent to prevent infinite recursion.
   *
   * @param modelId the root model to flatten
   * @return a self-contained model JSON with merged textures and elements, or {@code null}
   */
  private static JsonObject flattenModel(ResourceLocation modelId) {
    JsonObject current = readJson(modelId, "models");
    if (current == null) return null;

    JsonObject result = new JsonObject();
    JsonObject textures = new JsonObject();
    JsonArray elements = null;

    JsonObject step = current;
    int depth = 0;
    while (step != null && depth++ < 10) {
      if (step.has("textures")) {
        JsonObject tex = step.getAsJsonObject("textures");
        for (String k : tex.keySet()) {
          if (!textures.has(k)) textures.add(k, tex.get(k));
        }
      }
      if (!result.has("render_type") && step.has("render_type")) {
        result.add("render_type", step.get("render_type"));
      }
      if (elements == null && step.has("elements")) {
        elements = step.getAsJsonArray("elements").deepCopy();
      }
      if (step.has("parent")) {
        String parentStr = step.get("parent").getAsString();
        if (parentStr.startsWith("builtin/") || parentStr.contains("block/block")) break;

        ResourceLocation parentId = ResourceLocation.parse(parentStr.contains(":") ? parentStr : "minecraft:" + parentStr);
        step = readJson(parentId, "models");
      } else {
        break;
      }
    }

    if (elements == null) {
      elements = new JsonArray();
      JsonObject defaultCube = new JsonObject();
      JsonArray f = new JsonArray(); f.add(0); f.add(0); f.add(0);
      JsonArray t = new JsonArray(); t.add(16); t.add(16); t.add(16);
      defaultCube.add("from", f);
      defaultCube.add("to", t);

      JsonObject faces = new JsonObject();
      for (String dir : new String[]{"down", "up", "north", "south", "west", "east"}) {
        JsonObject face = new JsonObject();
        face.addProperty("texture", "#all");
        face.addProperty("cullface", dir);
        faces.add(dir, face);
      }
      defaultCube.add("faces", faces);
      elements.add(defaultCube);
    }

    result.addProperty("parent", "minecraft:block/block");
    if (textures.size() > 0) result.add("textures", textures);
    result.add("elements", elements);

    return result;
  }

  /**
   * Writes all pending block-tag mappings collected via {@link #addTagMapping} into
   * the runtime pack's {@code data/.../tags/block/...} directory.
   *
   * @throws Exception if any tag file cannot be written
   */
  private static void generateDynamicTags() throws Exception {
    for (Map.Entry<ResourceLocation, Set<ResourceLocation>> entry : PENDING_TAGS.entrySet()) {
      ResourceLocation tagLoc = entry.getKey();
      Path tagPath = PACK_DIR.resolve("data/" + tagLoc.getNamespace() + "/tags/block/" + tagLoc.getPath() + ".json");
      Files.createDirectories(tagPath.getParent());

      List<String> values = entry.getValue().stream().map(loc -> "\"" + loc.toString() + "\"").toList();
      String tagJson = "{\n  \"replace\": false,\n  \"values\":[\n    " + String.join(",\n    ", values) + "\n  ]\n}";
      writeJson(tagPath, tagJson);
    }
  }

  /**
   * Generates identical loot tables for both modern and legacy folder layouts so the
   * slab drops the parent block, and drops two when broken in the double state.
   *
   * @param target original block being replaced
   * @param slabName name of the generated slab block
   * @throws Exception if writing fails
   */
  private static void generateLootTable(ResourceLocation target, String slabName) throws Exception {
    Path lootDirModern = PACK_DIR.resolve("data/" + PtsMod.MODID + "/loot_table/blocks");
    Path lootDirLegacy = PACK_DIR.resolve("data/" + PtsMod.MODID + "/loot_tables/blocks");

    Files.createDirectories(lootDirModern);
    Files.createDirectories(lootDirLegacy);

    String lootJson = """
        {
          "type": "minecraft:block",
          "pools":[ {
            "rolls": 1,
            "entries":[ {
              "type": "minecraft:item",
              "name": "%1$s:%2$s",
              "functions":[ {
                "function": "minecraft:set_count",
                "conditions":[ {
                  "condition": "minecraft:block_state_property",
                  "block": "%3$s:%4$s",
                  "properties": { "type": "double" }
                } ],
                "count": 2,
                "add": false
              } ]
            } ],
            "conditions":[ { "condition": "minecraft:survives_explosion" } ]
          } ]
        }""".formatted(
            target.getNamespace(),  // %1$s → parent namespace
            target.getPath(),       // %2$s → parent block
            PtsMod.MODID,           // %3$s → slab namespace
            slabName                // %4$s → slab name
        );

    for (Path dir : java.util.List.of(lootDirModern, lootDirLegacy)) {
      writeJson(dir.resolve(slabName + ".json"), lootJson);
    }
  }
}