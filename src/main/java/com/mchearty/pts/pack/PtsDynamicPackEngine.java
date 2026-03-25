package com.mchearty.pts.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mchearty.pts.PtsMod;
import com.mchearty.pts.config.PtsConfigService;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.locating.IModFileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Robust JSON Pipeline deriving slab bounds and textures directly from true model parameters.
 *
 * <p>Generates a complete runtime resource pack containing blockstate definitions,
 * sliced block models (UV and geometry transformed for bottom/top slabs), loot
 * tables, and dynamic tag copies. The pack is rebuilt only when configuration
 * changes.
 */
public class PtsDynamicPackEngine {

  private static final Logger LOGGER = LoggerFactory.getLogger(PtsDynamicPackEngine.class);
  /** Root directory of the generated runtime pack inside the config folder. */
  public static final Path PACK_DIR = FMLPaths.CONFIGDIR.get().resolve("pts_generated_pack");
  private static final Path HASH_FILE = PACK_DIR.resolve("config.hash");

  /** Maps original tag locations to the set of PTS slab IDs that should be included. */
  private static final Map<ResourceLocation, Set<ResourceLocation>> PENDING_TAGS = new HashMap<>();

  /**
   * Records that a PTS slab should be added to the given tag.
   *
   * @param tagLoc the tag resource location
   * @param slabId the PTS slab ID
   */
  public static void addTagMapping(ResourceLocation tagLoc, ResourceLocation slabId) {
    PENDING_TAGS.computeIfAbsent(tagLoc, k -> new HashSet<>()).add(slabId);
  }

  /**
   * Ensures the runtime pack directory exists.
   */
  public static void initializePackDirectory() {
    try {
      if (!Files.exists(PACK_DIR)) Files.createDirectories(PACK_DIR);
    } catch (Exception ignored) {}
  }

  private static void writeJson(Path path, String content) throws Exception {
    try (BufferedWriter writer = Files.newBufferedWriter(path)) {
      writer.write(content);
    }
  }

  private static JsonObject readJson(ResourceLocation id, String type) {
    String path = "assets/" + id.getNamespace() + "/" + type + "/" + id.getPath() + ".json";
    IModFileInfo info = ModList.get().getModFileById(id.getNamespace());
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
   * Generates or updates the entire runtime resource pack if the configuration
   * hash has changed.
   *
   * @param targetIds set of original blocks that received PTS slabs
   */
  public static void generateOrUpdateRuntimePack(Set<ResourceLocation> targetIds) {
    try {
      String currentHash = PtsConfigService.calculateConfigHash();

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

  private static void transformBlockstate(ResourceLocation targetId, String slabName) throws Exception {
    JsonObject originalState = readJson(targetId, "blockstates");
    if (originalState == null) return;

    JsonObject newState = new JsonObject();
    if (originalState.has("variants")) {
      JsonObject newVariants = new JsonObject();
      JsonObject variants = originalState.getAsJsonObject("variants");

      for (String key : variants.keySet()) {
        JsonElement variantData = variants.get(key);

        for (String type : new String[]{"bottom", "top", "double"}) {
          String newKeyWlTrue = key.isEmpty() ? "type=" + type + ",waterlogged=true" : key + ",type=" + type + ",waterlogged=true";
          String newKeyWlFalse = key.isEmpty() ? "type=" + type + ",waterlogged=false" : key + ",type=" + type + ",waterlogged=false";

          JsonElement newVariantData = transformVariantData(variantData, targetId, type);
          newVariants.add(newKeyWlTrue, newVariantData);
          newVariants.add(newKeyWlFalse, newVariantData);
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
          newPart.add("apply", transformVariantData(part.get("apply"), targetId, type));
          newMultipart.add(newPart);
        }
      }
      newState.add("multipart", newMultipart);
    }

    Path statesDir = PACK_DIR.resolve("assets/" + PtsMod.MODID + "/blockstates");
    Files.createDirectories(statesDir);
    writeJson(statesDir.resolve(slabName + ".json"), newState.toString());
  }

  private static JsonElement transformVariantData(JsonElement data, ResourceLocation targetId, String type) throws Exception {
    if (data.isJsonArray()) {
      JsonArray arr = new JsonArray();
      for (JsonElement el : data.getAsJsonArray()) {
        arr.add(transformSingleVariant(el.getAsJsonObject(), targetId, type));
      }
      return arr;
    } else {
      return transformSingleVariant(data.getAsJsonObject(), targetId, type);
    }
  }

  private static JsonObject transformSingleVariant(JsonObject variant, ResourceLocation targetId, String type) throws Exception {
    JsonObject newVariant = variant.deepCopy();
    String modelStr = variant.get("model").getAsString();
    ResourceLocation modelId = ResourceLocation.parse(modelStr.contains(":") ? modelStr : "minecraft:" + modelStr);

    String newModelName = targetId.getNamespace() + "_" + modelId.getPath().replace("/", "_") + "_" + type;

    JsonObject slicedModel = resolveAndSliceModel(modelId, type);
    if (slicedModel != null) {
      Path modelsDir = PACK_DIR.resolve("assets/" + PtsMod.MODID + "/models/block");
      Files.createDirectories(modelsDir);
      writeJson(modelsDir.resolve(newModelName + ".json"), slicedModel.toString());
    }

    newVariant.addProperty("model", PtsMod.MODID + ":block/" + newModelName);
    return newVariant;
  }

  private static JsonObject resolveAndSliceModel(ResourceLocation baseModelId, String type) {
    JsonObject flattened = flattenModel(baseModelId);
    if (flattened == null) return null;

    JsonObject sliced = flattened.deepCopy();
    if (!sliced.has("elements")) return sliced;

    JsonArray elements = sliced.getAsJsonArray("elements");
    JsonArray newElements = new JsonArray();

    for (JsonElement elem : elements) {
      JsonObject el = elem.getAsJsonObject();
      JsonArray from = el.getAsJsonArray("from");
      JsonArray to = el.getAsJsonArray("to");

      float minY = from.get(1).getAsFloat();
      float maxY = to.get(1).getAsFloat();

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
          if (faceName.equals("north") || faceName.equals("south") || faceName.equals("east") || faceName.equals("west")) {
            JsonArray uv;
            if (face.has("uv")) {
              uv = face.getAsJsonArray("uv");
            } else {
              uv = new JsonArray();
              uv.add(0); uv.add(0); uv.add(16); uv.add(16);
            }
            float u1 = uv.get(0).getAsFloat();
            float v1 = uv.get(1).getAsFloat();
            float u2 = uv.get(2).getAsFloat();
            float v2 = uv.get(3).getAsFloat();

            if (type.equals("bottom")) {
              v1 = v1 + (v2 - v1) * 0.5f;
            } else if (type.equals("top")) {
              v2 = v1 + (v2 - v1) * 0.5f;
            }

            JsonArray newUv = new JsonArray();
            newUv.add(u1); newUv.add(v1); newUv.add(u2); newUv.add(v2);
            face.add("uv", newUv);
          }
        }
      }
      newElements.add(el);
    }
    sliced.add("elements", newElements);
    return sliced;
  }

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

  private static void generateDynamicTags() throws Exception {
    for (Map.Entry<ResourceLocation, Set<ResourceLocation>> entry : PENDING_TAGS.entrySet()) {
      ResourceLocation tagLoc = entry.getKey();
      Path tagPath = PACK_DIR.resolve("data/" + tagLoc.getNamespace() + "/tags/block/" + tagLoc.getPath() + ".json");
      Files.createDirectories(tagPath.getParent());

      java.util.List<String> values = entry.getValue().stream().map(loc -> "\"" + loc.toString() + "\"").toList();
      String tagJson = "{\n  \"replace\": false,\n  \"values\":[\n    " + String.join(",\n    ", values) + "\n  ]\n}";
      writeJson(tagPath, tagJson);
    }
  }

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
              "name": "%3$s:%4$s",
              "functions":[ {
                "function": "minecraft:set_count",
                "conditions":[ { "condition": "minecraft:block_state_property", "block": "%3$s:%4$s", "properties": { "type": "double" } } ],
                "count": 2,
                "add": false
              } ]
            } ],
            "conditions":[ { "condition": "minecraft:survives_explosion" } ]
          } ]
        }""".formatted(target.getNamespace(), target.getPath(), PtsMod.MODID, slabName);

    writeJson(lootDirModern.resolve(slabName + ".json"), lootJson);
    writeJson(lootDirLegacy.resolve(slabName + ".json"), lootJson);
  }
}