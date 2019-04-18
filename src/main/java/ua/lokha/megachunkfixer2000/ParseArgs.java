package ua.lokha.megachunkfixer2000;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Data
@AllArgsConstructor
public class ParseArgs {

    private static final FilenameFilter filter = (dir, name) -> name.endsWith(".mca");

    private File dir;
    private List<File> regions;
    private List<String> flags;

    @SuppressWarnings("ConstantConditions")
    public static ParseArgs parse(String[] args) {
        List<String> flags = Stream.of(args).filter(s -> s.startsWith("--")).collect(Collectors.toList());

        File dir;
        if (args.length > 0) {
            dir = new File(String.join(" ", Stream.of(args).filter(s -> !s.startsWith("--")).collect(Collectors.joining(" "))));
            if (!dir.exists()) {
                throw new RuntimeException("Папка " + dir + " не найдена.");
            } else if (!dir.isDirectory()) {
                throw new RuntimeException("Это не папка - " + dir + ".");
            }
        } else {
            dir = new File(".");
        }

        List<File> files = new ArrayList<>(Arrays.asList(dir.listFiles(filter)));
        File regionDir = new File(dir, "region");
        if (regionDir.exists() && regionDir.isDirectory()) {
            files.addAll(Arrays.asList(regionDir.listFiles(filter)));
        }

        return new ParseArgs(dir, files, flags);
    }

    public boolean hasFlag(String flag) {
        return flags.contains(flag);
    }
}
