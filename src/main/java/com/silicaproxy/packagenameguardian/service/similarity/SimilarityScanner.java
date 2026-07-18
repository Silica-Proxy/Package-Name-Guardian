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


package com.silicaproxy.packagenameguardian.service.similarity;

import com.silicaproxy.packagenameguardian.service.sync.ReferenceSnapshot.EcosystemSnapshot;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.text.similarity.FuzzyScore;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Typosquat detection: a candidate popular-package name is "flagged" iff Levenshtein distance
 * crosses its threshold AND at least one of Jaro-Winkler/FuzzyScore also crosses its threshold,
 * against the request's (normalized) name. Levenshtein is mandatory (not just "any 2 of 3")
 * because Jaro-Winkler and FuzzyScore both reward a long shared prefix heavily on their own --
 * real sibling packages from the same publisher (e.g. {@code spring-context}/{@code spring-aop},
 * {@code commons-text}/{@code commons-io}) share exactly that shape and would otherwise collide
 * with each other, confirmed against a real deps.dev top-10,000-per-ecosystem snapshot
 * (RealDataSimilarityScannerTest). Levenshtein is a global edit-distance measure, not
 * prefix-biased, so requiring it filters out that specific false-positive shape without loosening
 * detection of genuine typosquats (which resemble the whole name, not just a shared prefix). The
 * request itself is {@code BLOCKED} iff any candidate in the ecosystem's reference set is
 * flagged. No allowlist semantics: a name that resembles nothing popular is simply
 * {@code ALLOWED}.
 *
 * Performance: the Levenshtein instance is bounded to {@code LEVENSHTEIN_THRESHOLD_MAX}, so
 * computation abandons early when distance exceeds the threshold, returning -1 as a sentinel. The
 * loop skips Jaro-Winkler and FuzzyScore computation for candidates that don't satisfy
 * Levenshtein, eliminating wasteful scoring of unmatchable names.
 */
@Component
@NullMarked
public class SimilarityScanner {

    // Names shorter than this are too noisy for these metrics (e.g. 2-char names are trivially
    // "close" to many others under Levenshtein/Jaro-Winkler).
    private static final int MIN_NAME_LENGTH = 3;

    // Levenshtein distance is always >= |len(a) - len(b)|; the threshold formula below never
    // exceeds 4, so no candidate whose length differs from the input by more than this can ever
    // be flagged by any of the 3 legs. Pruning on this bound is therefore safe, not approximate.
    private static final int MAX_SAFE_LENGTH_DIFF = 4;

    private static final int LEVENSHTEIN_THRESHOLD_MIN = 2;
    private static final int LEVENSHTEIN_THRESHOLD_MAX = 4;
    private static final double LEVENSHTEIN_THRESHOLD_RATIO = 0.20;

    // Commonly cited 0.90-0.95 "high confidence" band in record-linkage literature; biased
    // toward fewer false positives since this blocks real installs.
    private static final double JARO_WINKLER_THRESHOLD = 0.92;

    private static final double FUZZY_SCORE_THRESHOLD = 0.60;

    private static final LevenshteinDistance LEVENSHTEIN = new LevenshteinDistance(LEVENSHTEIN_THRESHOLD_MAX);
    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();
    private static final FuzzyScore FUZZY_SCORE = new FuzzyScore(Locale.ROOT);

    public record ScanResult(boolean flagged, @Nullable String flaggedCandidate, @Nullable AlgorithmScores scores) {

        static final ScanResult NOT_FLAGGED = new ScanResult(false, null, null);
    }

    public record AlgorithmScores(int levenshteinDistance, double jaroWinklerSimilarity, double fuzzyScoreNormalized) {
    }

    public ScanResult scan(String normalizedName, EcosystemSnapshot ecosystemData) {
        if (normalizedName.length() < MIN_NAME_LENGTH) {
            return ScanResult.NOT_FLAGGED;
        }
        // Exact match short-circuits to ALLOWED: a package is never flagged against itself.
        if (ecosystemData.exactNames().contains(normalizedName)) {
            return ScanResult.NOT_FLAGGED;
        }

        int inputLength = normalizedName.length();
        Map<Integer, List<String>> candidatesInRange = ecosystemData.namesByLength().subMap(
                inputLength - MAX_SAFE_LENGTH_DIFF, true,
                inputLength + MAX_SAFE_LENGTH_DIFF, true);

        for (List<String> namesOfSameLength : candidatesInRange.values()) {
            for (String candidate : namesOfSameLength) {
                AlgorithmScores scores = tryFlag(normalizedName, candidate);
                if (scores != null) {
                    return new ScanResult(true, candidate, scores);
                }
            }
        }
        return ScanResult.NOT_FLAGGED;
    }

    private @Nullable AlgorithmScores tryFlag(String input, String candidate) {
        int threshold = levenshteinThreshold(candidate.length());
        int distance = LEVENSHTEIN.apply(input, candidate);
        // Bounded instance returns -1 when distance exceeds threshold (early abandon). -1 is a
        // sentinel value indicating "too far"; it must not be treated as <= threshold.
        if (distance == -1 || distance > threshold) {
            return null;
        }
        double jaroWinklerSim = JARO_WINKLER.apply(input, candidate);
        if (jaroWinklerSim < JARO_WINKLER_THRESHOLD) {
            double fuzzyScoreNormalized = normalizedFuzzyScore(input, candidate);
            if (fuzzyScoreNormalized < FUZZY_SCORE_THRESHOLD) {
                return null;
            }
            return new AlgorithmScores(distance, jaroWinklerSim, fuzzyScoreNormalized);
        }
        double fuzzyScoreNormalized = normalizedFuzzyScore(input, candidate);
        return new AlgorithmScores(distance, jaroWinklerSim, fuzzyScoreNormalized);
    }

    // clamp(2, floor(len*0.20), 4): a fixed distance of 2 is too loose for 2-char names and too
    // tight for 15-char ones. Scaled off the candidate's own length since the reference set is
    // the fixed, known-good side of the comparison.
    private int levenshteinThreshold(int candidateLength) {
        int scaled = (int) Math.floor(candidateLength * LEVENSHTEIN_THRESHOLD_RATIO);
        return Math.clamp(scaled, LEVENSHTEIN_THRESHOLD_MIN, LEVENSHTEIN_THRESHOLD_MAX);
    }

    // FuzzyScore's raw score isn't length-normalized out of the box: normalize it as
    // fuzzyScore(candidate, input) / fuzzyScore(candidate, candidate) -- a deliberate choice,
    // not how the class is used elsewhere (it's designed for ranking search results, not
    // thresholding a bounded similarity ratio).
    private double normalizedFuzzyScore(String input, String candidate) {
        int rawScore = FUZZY_SCORE.fuzzyScore(candidate, input);
        int maxScore = FUZZY_SCORE.fuzzyScore(candidate, candidate);
        return maxScore == 0 ? 0.0 : (double) rawScore / maxScore;
    }
}
