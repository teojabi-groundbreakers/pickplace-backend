package com.pickplace.backend.ingestion.validation.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.pickplace.backend.ingestion.validation.model.ValidationRow;
import static org.assertj.core.api.Assertions.*;
import static com.pickplace.backend.ingestion.validation.service.ValidationFixtures.*;

class PartitionedRowProcessorTests {
    @TempDir Path temp;

    @Test void identicalDuplicatesKeepOnlyFirstCountAcrossMembers() throws Exception {
        var c = store("STORE1"); var validator = validator("STORE_SNAPSHOT");
        var result = new ArrayList<ValidationRow>();
        try (var partitions = new PartitionedRowProcessor(temp.resolve("parts"), JSON)) {
            partitions.add(c, validator.validate(c, new ValidationRow.Position(2, "a.csv", 1)));
            partitions.add(c, validator.validate(c, new ValidationRow.Position(3, "b.csv", 2)));
            partitions.finish(result::add);
        }
        assertThat(result).extracting("disposition").containsExactly("ACCEPTED", "DUPLICATE");
        assertThat(result.get(1).position().member()).isEqualTo("b.csv");
        assertThat(temp.resolve("parts")).doesNotExist();
    }

    @Test void conflictingIdHoldsAllRowsIncludingTheFirstAndAnyRepeatedCopy() throws Exception {
        var first = store("STORE1"); var other = store("STORE1"); other.set(31, "주소 변경");
        var validator = validator("STORE_SNAPSHOT"); var result = new ArrayList<ValidationRow>();
        try (var partitions = new PartitionedRowProcessor(temp.resolve("parts"), JSON)) {
            long n = 0;
            for (var cells : List.of(first, other, first))
                partitions.add(cells, validator.validate(cells, new ValidationRow.Position(2, "a.csv", ++n)));
            partitions.finish(result::add);
        }
        assertThat(result).hasSize(3).allMatch(r -> r.disposition().equals("HOLD"));
        assertThat(result).allMatch(r -> r.issues().stream().anyMatch(i -> i.code().equals("DUPLICATE_ID_CONFLICT")));
    }

    @Test void caseDifferentIdsStayDistinct() throws Exception {
        var validator = validator("STORE_SNAPSHOT"); var result = new ArrayList<ValidationRow>();
        try (var partitions = new PartitionedRowProcessor(temp.resolve("parts"), JSON)) {
            for (String id : List.of("Store1", "STORE1")) {
                var c = store(id); partitions.add(c, validator.validate(c, POSITION));
            }
            partitions.finish(result::add);
        }
        assertThat(result).hasSize(2).allMatch(r -> r.disposition().equals("ACCEPTED"));
    }
}
