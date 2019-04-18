# mega-chunk-fixer-2000
Fixed minecraft chunks.

## Исправление чанков

Эта штука выявляет кривые чанки в region файлах и удаляет их (чанки).

*  Способ 1: Пишем команду `java -jar mega-chunk-fixer-2000.jar ПУСТЬ_К_МИРУ`
*  Способ 2: Пишем команду `java -jar mega-chunk-fixer-2000.jar ПУСТЬ_К_ПАПКЕ_С_ФАЙЛАМИ *.mca`
*  Способ 3: Закидываем программу в папку мира и выполняем команду `java -jar mega-chunk-fixer-2000.jar`
*  Способ 4: Закидываем программу в папку с `*.mca` файлами и выполняем команду `java -jar mega-chunk-fixer-2000.jar`

## Флаги
Флаги позволяют добавлять функционала в процесс исправления чанков.  
Флаги указывается в конце команды запуска программы, пример `java -jar mega-chunk-fixer-2000.jar ПУСТЬ_К_МИРУ --флаг`

Список флагов:
*  `--clean-unused-space` - очищаем неиспользуемое пространство в файлах регионов, которое возникает в процессе перезаписывания чанков сервером.

# API
Вы можете использовать эту утилиту в качестве библиотеки.

Maven:
```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.lokha</groupId>
    <artifactId>mega-chunk-fixer-2000</artifactId>
    <version>3.0</version>
</dependency>
```

Удаление чанка:  
*Размер файла региона не изменится, подробнее ниже.*
```java
int chunkX = 2; // относительная координата от 0 до 31!
int chunkZ = 3; // относительная координата от 0 до 31!
File file = new File("r.0.0.mca");
try (RegionFile regionFile = new RegionFile(file)) {
    regionFile.deleteChunk(chunkX, chunkZ);
}
```

Очистка неиспользуемого пространства:
```java
File file = new File("r.0.0.mca");
try (RegionFile regionFile = new RegionFile(file)) {
    regionFile.clearUnusedSpace();
}
```

Метод `RegionFile::deleteChunk` не уменьшает файл региона, он лишь помечает чанк удаленным, и это нормально.  
Чтобы очистить место, которое занимал чанк, нужно переместить остальные чанки на его место и обрезать файл региона, нужно вызвать `RegionFile::clearUnusedSpace`:
```java
// удаляем чанки
regionFile.deleteChunk(chunkX1, chunkZ1);
regionFile.deleteChunk(chunkX2, chunkZ2);

// чистим место
regionFile.clearUnusedSpace();
```