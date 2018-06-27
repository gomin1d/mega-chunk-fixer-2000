package ua.lokha.megachunkfixer2000;

import java.io.*;

import java.util.*;

@SuppressWarnings({"UtilityClassCanBeEnum", "UtilityClass", "unchecked"})
public class Main {

    private static Map<String, Object> emptyChunkExample = new HashMap<>();

    private static final long LastUpdateDef = 537158390L;

    static {
        Map<String, Object> levelMap = new HashMap<>();
        levelMap.put("xPos", 0);
        levelMap.put("zPos", 0);
        levelMap.put("LastUpdate", LastUpdateDef);
        levelMap.put("LightPopulated", (byte) 0);
        levelMap.put("HeightMap", new int[256]);
        levelMap.put("Sections", new ArrayList<>());
        levelMap.put("Biomes", new byte[256]);
        levelMap.put("InhabitedTime", 0L);
        levelMap.put("TileEntities", new ArrayList<>());
        levelMap.put("TerrainPopulated", (byte) 0);
        levelMap.put("Entities", new ArrayList<>());

        emptyChunkExample.put("Level", levelMap);
        emptyChunkExample.put("DataVersion", 1343);
    }

    public static Map<String, Object> getEmptyChunk(int x, int z, long LastUpdate) {
        Map<String, Object> levelMap = (Map<String, Object>) emptyChunkExample.get("Level");
        levelMap.put("xPos", x);
        levelMap.put("zPos", z);
        levelMap.put("LastUpdate", LastUpdate);

        return emptyChunkExample;
    }

    private static final FilenameFilter filter = (dir, name) -> name.endsWith(".mca");

    @SuppressWarnings("ConstantConditions")
    public static void main(String[] args) throws Exception {

        File dir;
        if (args.length > 0) {
            dir = new File(String.join(" ", args));
            if (!dir.exists()) {
                System.out.println("Папка " + dir + " не найдена.");
                return;
            } else if (!dir.isDirectory()) {
                System.out.println("Это не папка - " + dir + ".");
                return;
            }
        } else {
            dir = new File(".");
        }

        List<File> files = new ArrayList<>(Arrays.asList(dir.listFiles(filter)));
        File regionDir = new File(dir, "region");
        if (regionDir.exists() && regionDir.isDirectory()) {
            files.addAll(Arrays.asList(regionDir.listFiles(filter)));
        }


        System.out.println("Начинаем фиксить регионы в текущей папке, найдено " + files.size() + " файлов типа *.mca.");

        int div10 = files.size() / 10;
        int fixedTotal = 0;

        for (int i = 0; i < files.size(); i++) {
            try {
                fixedTotal += fixRegion(files.get(i));
            } catch (Exception e) {
                System.out.println("Ошибка обработки региона " + files.get(i).getName());
                e.printStackTrace();
            } finally {
                if (div10 != 0 && i % div10 == 0) {
                    System.out.println("Проверяем чанки " + i + "/" + files.size());
                }
            }
        }

        System.out.println("Всего было исправлено " + fixedTotal + " чанков.");
    }

    private static int fixRegion(File file) throws IOException {
        int fixed = 0;

        RegionFile regionFile = new RegionFile(file);
        long LastUpdateMax = LastUpdateDef;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if (regionFile.hasChunk(x, z)) {
                    try (DataInputStream inputStream = regionFile.getChunkDataInputStream(x, z)) {
                        Map<String, Object> read = NBTStreamReader.read(inputStream, false);
                        Map<String, Object> level = (Map<String, Object>) read.get("Level");
                        Long lastUpdate = (Long) level.get("LastUpdate");
                        if (lastUpdate != null) {
                            LastUpdateMax = Math.max(LastUpdateMax, lastUpdate);
                        }
                    } catch (Exception e) {
                        System.out.println("Ошибка считывания чанка file=" + file.getName() + " x=" + x + " z=" + z + ": " + e + ". Удаляем чанк...");
                        try (DataOutputStream outputStream = regionFile.getChunkDataOutputStream(x, z)) {
                            NBTStreamWriter.write(outputStream, getEmptyChunk(x, z, LastUpdateMax), false);
                            fixed++;
                        }
                    }
                }
            }
        }
        return fixed;
    }
}
