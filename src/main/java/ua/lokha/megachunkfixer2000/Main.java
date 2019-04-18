package ua.lokha.megachunkfixer2000;

import ua.lokha.megachunkfixer2000.logger.LoggerInstaller;

import java.io.*;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

@SuppressWarnings({"UtilityClassCanBeEnum", "UtilityClass", "unchecked"})
public class Main {

    private static DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    public static void main(String[] args) throws Exception {

        final Logger logger = LoggerInstaller.create("mega-chunk-fixer-2000", "mega-chunk-fixer-2000.log");
        logger.info("Старт исправления чанков " + dateFormat.format(new Date()));

        ParseArgs parseArgs = ParseArgs.parse(args);
        List<File> files = parseArgs.getRegions();
        boolean cleanUnusedSpace = parseArgs.hasFlag("--clean-unused-space");

        if (cleanUnusedSpace) {
            System.out.println("Обнаружен флаг --clean-unused-space, будет выполнена очистка неиспользуемого пространства в регионах.");
        }
        System.out.println("Начинаем фиксить регионы в папке " + parseArgs.getDir() + ", найдено " + files.size() + " файлов типа *.mca.");

        int deletedTotal = 0;
        int beforeCleanUsedTotal = 0;
        int afterCleanUsedTotal = 0;

        int div10 = files.size() / 10;
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            try {
                if (cleanUnusedSpace) {
                    beforeCleanUsedTotal += file.length();
                }
                deletedTotal += fixRegion(file, cleanUnusedSpace);
                if (cleanUnusedSpace) {
                    afterCleanUsedTotal += file.length();
                }
            } catch (Exception e) {
                System.out.println("Ошибка обработки региона " + file.getName());
                e.printStackTrace();
            } finally {
                if (div10 != 0 && i % div10 == 0) {
                    System.out.println("Проверяем регионы " + i + "/" + files.size());
                }
            }
        }

        System.out.println("Всего было удалено " + deletedTotal + " чанков.");
        if (cleanUnusedSpace) {
            System.out.println("Было очищено пространство " +
                    "с " + Utils.toLogLength(beforeCleanUsedTotal) + " " +
                    "до " + Utils.toLogLength(afterCleanUsedTotal) + " " +
                    "(-" + Utils.toLogPercent(afterCleanUsedTotal, beforeCleanUsedTotal) + "%).");
        }
    }

    private static int fixRegion(File file, boolean clearUnusedSpace) {
        int deleted = 0;

        try (RegionFile regionFile = new RegionFile(file)) {
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    if (regionFile.hasChunk(x, z)) {
                        boolean deleteChunk = false;
                        Map<String, Object> root;
                        try (DataInputStream inputStream = regionFile.getChunkDataInputStream(x, z)) {
                            root = NBTStreamReader.read(inputStream, false);
                            Map<String, Object> level = (Map<String, Object>) root.get("Level");

                            if (checkPos(regionFile, level, x, z)) {
                                deleteChunk = true;
                            } else if (checkSections(regionFile, level, x, z)) {
                                deleteChunk = true;
                            }
                        } catch (Exception e) {
                            System.out.println("Ошибка считывания чанка file=" + file.getName() + " x=" + x + " z=" + z + ": " + e + ". " +
                                    "Удаляем чанк...");
                            deleteChunk = true;
                        }

                        if (deleteChunk) {
                            deleted++;
                            try {
                                regionFile.deleteChunk(x, z);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }

            if (clearUnusedSpace) {
                regionFile.clearUnusedSpace();
            }
        }
        return deleted;
    }

    private static boolean checkPos(RegionFile regionFile, Map<String, Object> level, int x, int z) {
        return Try.ignore(() -> {
            final String[] regionNameData = regionFile.getFileName().split("\\.");
            int regionX = Integer.parseInt(regionNameData[1]);
            int regionZ = Integer.parseInt(regionNameData[2]);

            final Number xPos = (Number) level.get("xPos");
            final Number zPos = (Number) level.get("zPos");
            final int realX = (regionX << 5) + x;
            final int realZ = (regionZ << 5) + z;

            if (xPos == null
                    || xPos.intValue() != realX
                    || zPos == null
                    || zPos.intValue() != realZ) {
                System.out.println("Чанк file=" + regionFile.getFileName() + " x=" + x + " z=" + z +
                        " находится не на тех координатах " + xPos + "," + zPos +
                        ", а нужно " + realX + "," + realZ + ". " +
                        "Удаляем чанк...");
                return true;
            }
            return false;
        }, false);
    }

    private static boolean checkSections(RegionFile regionFile, Map<String, Object> level, int x, int z) {
        return Try.ignore(() -> {
            AtomicBoolean deleteChunk = new AtomicBoolean(false);

            final List<Map<String, Object>> sections = (List<Map<String, Object>>) level.get("Sections");
            final Iterator<Map<String, Object>> iterator = sections.iterator();
            while (iterator.hasNext() && !deleteChunk.get()) {
                final Map<String, Object> section = iterator.next();
                try {
                    final byte[] blocks = (byte[]) section.get("Blocks");
                    final byte[] skyLights = (byte[]) section.get("SkyLight");
                    final byte[] blockLights = (byte[]) section.get("BlockLight");
                    final byte[] data = (byte[]) section.get("Data");
                    final byte[] add = (byte[]) section.get("Add");

                    final Number y = (Number) section.get("Y");

                    if (blocks == null) {
                        System.out.println("В чанке file=" + regionFile.getFileName() + " x=" + x + " z=" + z +
                                " в секции y=" + y + " отсутствует массив Blocks. " +
                                "Удаляем чанк...");
                        deleteChunk.set(true);
                    } else if (data == null) {
                        System.out.println("В чанке file=" + regionFile.getFileName() + " x=" + x + " z=" + z +
                                " в секции y=" + y + " отсутствует массив Data. " +
                                "Удаляем чанк...");
                        deleteChunk.set(true);
                    } else if (skyLights == null) {
                        System.out.println("В чанке file=" + regionFile.getFileName() + " x=" + x + " z=" + z +
                                " в секции y=" + y + " отсутствует массив SkyLight. " +
                                "Удаляем чанк...");
                        deleteChunk.set(true);
                    } else if (blockLights == null) {
                        System.out.println("В чанке file=" + regionFile.getFileName() + " x=" + x + " z=" + z +
                                " в секции y=" + y + " отсутствует массив BlockLight. " +
                                "Удаляем чанк...");
                        deleteChunk.set(true);
                    } else if (blocks.length != 4096) {
                        System.out.println("В чанке file=" + regionFile.getFileName() + " x=" + x + " z=" + z +
                                " секция y=" + y + " содержит массив Blocks неправильной длины " + blocks.length + ", а нужно 4096. " +
                                "Удаляем чанк...");
                        deleteChunk.set(true);
                    } else if (data.length != 2048) {
                        System.out.println("В чанке file=" + regionFile.getFileName() + " x=" + x + " z=" + z +
                                " секция y=" + y + " содержит массив Data неправильной длины " + data.length + ", а нужно 2048. " +
                                "Удаляем чанк...");
                        deleteChunk.set(true);
                    } else if (blockLights.length != 2048) {
                        System.out.println("В чанке file=" + regionFile.getFileName() + " x=" + x + " z=" + z +
                                " секция y=" + y + " содержит массив BlockLight неправильной длины " + blockLights.length + ", а нужно 2048. " +
                                "Удаляем чанк...");
                        deleteChunk.set(true);
                    } else if (skyLights.length != 2048) {
                        System.out.println("В чанке file=" + regionFile.getFileName() + " x=" + x + " z=" + z +
                                " секция y=" + y + " содержит массив SkyLight неправильной длины " + skyLights.length + ", а нужно 2048. " +
                                "Удаляем чанк...");
                        deleteChunk.set(true);
                    } else if (add != null && add.length != 2048) {
                        System.out.println("В чанке file=" + regionFile.getFileName() + " x=" + x + " z=" + z +
                                " секция y=" + y + " содержит массив Add неправильной длины " + add.length + ", а нужно 2048. " +
                                "Удаляем чанк...");
                        deleteChunk.set(true);
                    }
                } catch (Exception e) {
                    System.out.println("В чанке file=" + regionFile.getFileName() + " x=" + x + " z=" + z + " " +
                            "произошла ошибка при обработке секции " + section);
                    e.printStackTrace();
                }
            }

            return deleteChunk.get();
        }, false);
    }
}
