package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.GradeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 013 T073. */
class LetterGradesTest {

    private static final List<String> ORDER =
            List.of("A+", "A", "A-", "B+", "B", "B-", "C+", "C", "C-", "D", "F");

    /** The real, shipped ladder, bound exactly the way Spring binds it. */
    private static GradeProperties shipped() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("weights", new FileSystemResource("../config/weights.yml"));
        MutablePropertySources mps = new MutablePropertySources();
        sources.forEach(mps::addLast);
        return new Binder(ConfigurationPropertySources.from(mps))
                .bind("draftsim.grades", GradeProperties.class).get();
    }

    @Test
    void higherRankNeverGetsALowerGrade() throws Exception {
        LetterGrades g = new LetterGrades(shipped());
        for (int n : new int[] {10, 12, 14}) {
            int prev = -1;
            for (int rank = 1; rank <= n; rank++) {
                String grade = g.grade(rank, n);
                assertNotNull(grade, n + " teams, rank " + rank);
                int idx = ORDER.indexOf(grade);
                assertTrue(idx >= prev, n + " teams: rank " + rank + " got " + grade + " after index " + prev);
                prev = idx;
            }
        }
    }

    @Test
    void extremesGetTheFirstAndLastGrade() throws Exception {
        LetterGrades g = new LetterGrades(shipped());
        for (int n : new int[] {2, 10, 12, 14}) {
            assertEquals("A+", g.grade(1, n));
            assertEquals("F", g.grade(n, n));
        }
        assertEquals("A+", g.grade(1, 1), "a one-team league is its own best");
    }

    @Test
    void tiedRanksShareAGrade() throws Exception {
        LetterGrades g = new LetterGrades(shipped());
        record T(String name, double v) {}
        List<T> ts = List.of(new T("a", 9), new T("b", 9), new T("c", 5), new T("d", 1));
        Map<T, Integer> ranks = LetterGrades.ranksDescending(ts, T::v, t -> true);
        assertEquals(1, ranks.get(ts.get(0)));
        assertEquals(1, ranks.get(ts.get(1)));
        assertEquals(3, ranks.get(ts.get(2)));
        assertEquals(g.grade(ranks.get(ts.get(0)), 4), g.grade(ranks.get(ts.get(1)), 4));
    }

    @Test
    void unrankedEntriesAreNotCounted() {
        record T(Double v) {}
        List<T> ts = List.of(new T(2.0), new T(null), new T(1.0));
        Map<T, Integer> ranks = LetterGrades.ranksDescending(ts, t -> t.v(), t -> t.v() != null);
        assertEquals(2, ranks.size());
        assertFalse(ranks.containsKey(ts.get(1)));
    }

    @Test
    void anInvalidPresentBlockFails() {
        assertThrows(IllegalArgumentException.class, () -> new GradeProperties(List.of(
                new GradeProperties.Cutoff(50, "A"), new GradeProperties.Cutoff(50, "B"),
                new GradeProperties.Cutoff(100, "F"))), "not strictly increasing");
        assertThrows(IllegalArgumentException.class, () -> new GradeProperties(List.of(
                new GradeProperties.Cutoff(50, "A"), new GradeProperties.Cutoff(90, "F"))), "last must be 100");
        assertThrows(IllegalArgumentException.class, () -> new GradeProperties(List.of(
                new GradeProperties.Cutoff(100, " "))), "blank grade");
    }

    @Test
    void aMissingBlockGivesNullGradesAndStillStarts() {
        GradeProperties missing = new Binder(ConfigurationPropertySources.from(
                new MutablePropertySources() {{ addLast(new MapPropertySource("empty", Map.of())); }}))
                .bindOrCreate("draftsim.grades", GradeProperties.class);
        assertFalse(missing.loaded());
        assertNull(new LetterGrades(missing).grade(1, 12));
        assertNull(new LetterGrades(new GradeProperties(null)).grade(1, 12));
    }
}
