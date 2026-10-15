package com.example.airportagent.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AirportDataLoaderFolderTest {

    private static final List<String> FILES = List.of("airports.csv", "aliases.csv", "distance_buckets.csv",
            "enplanements.csv", "ontime.csv", "traffic.csv", "manifest.json");

    /** Checks that the loader reads the same data from a folder as from the class path. */
    @Test
    void theLoaderReadsTheSameDataFromAFolderAsFromTheClassPath(@TempDir Path folder) throws IOException {
        for (String name : FILES) {
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream("data/" + name)) {
                Files.copy(stream, folder.resolve(name));
            }
        }

        AirportDataset fromFolder = new AirportDataLoader(folder).load();
        AirportDataset fromClassPath = new AirportDataLoader().load();

        assertThat(fromFolder.airports()).hasSameSizeAs(fromClassPath.airports());
        assertThat(fromFolder.traffic()).isEqualTo(fromClassPath.traffic());
        assertThat(fromFolder.info()).isEqualTo(fromClassPath.info());
    }
}
