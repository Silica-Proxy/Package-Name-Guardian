/*
 * Copyright 2026 SilicaProxy Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.silicaproxy.packagenameguardian.refdatatool;

import static org.assertj.core.api.Assertions.assertThat;

import com.silicaproxy.packagenameguardian.refdatatool.DepsDevBigQueryClient.PackageRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReferenceDataSqlGeneratorTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-08-01T02:30:00Z");

    @Test
    void emitsDeleteBeforeAnyInsert() {
        String sql = ReferenceDataSqlGenerator.buildSql(
                List.of(new PackageRow("NPM", "lodash", 1000, 1)), GENERATED_AT);

        int deleteIndex = sql.indexOf("DELETE FROM reference_package;");
        int insertIndex = sql.indexOf("INSERT INTO reference_package");
        assertThat(deleteIndex).isPositive();
        assertThat(insertIndex).isGreaterThan(deleteIndex);
    }

    @Test
    void batchesInsertsAtFiveHundredRowsPerStatement() {
        List<PackageRow> rows = new ArrayList<>();
        for (int i = 1; i <= 1200; i++) {
            rows.add(new PackageRow("NPM", "package-" + i, i, i));
        }

        String sql = ReferenceDataSqlGenerator.buildSql(rows, GENERATED_AT);

        int statementCount = countOccurrences(sql, "INSERT INTO reference_package");
        assertThat(statementCount).isEqualTo(3);
        assertThat(countOccurrences(sql, "('NPM', 'package-")).isEqualTo(1200);
    }

    @Test
    void escapesEmbeddedSingleQuotesInPackageNames() {
        String sql = ReferenceDataSqlGenerator.buildSql(
                List.of(new PackageRow("NPM", "weird'name", 1, 1)), GENERATED_AT);

        assertThat(sql).contains("'weird''name'");
    }

    @Test
    void headerReportsPerEcosystemRowCounts() {
        List<PackageRow> rows = List.of(
                new PackageRow("NPM", "a", 1, 1),
                new PackageRow("NPM", "b", 1, 2),
                new PackageRow("PYPI", "c", 1, 1));

        String sql = ReferenceDataSqlGenerator.buildSql(rows, GENERATED_AT);

        assertThat(sql).contains("NPM=2, PYPI=1");
    }

    private static int countOccurrences(String haystack, String needle) {
        Pattern pattern = Pattern.compile(Pattern.quote(needle));
        Matcher matcher = pattern.matcher(haystack);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
