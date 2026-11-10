package io.github.complydoc4j.hidden;

import java.util.List;

/**
 * A passage of text a file's own formatting hides.
 *
 * @param page the page, from 1; null in a format without pages
 * @param visibility {@code suspected} or {@code confirmed}
 * @param onlyIfInstruction hidden the way documents routinely hide things (a line run off the
 *     page, a figure's labels under the figure), so reported only when it reads as an
 *     instruction
 */
public record HiddenRun(Integer page, String text, String visibility, List<String> reasons,
        boolean onlyIfInstruction) {

    public HiddenRun {
        reasons = List.copyOf(reasons);
    }

    /** What a check of a file found, what it could not check, and whether it could read it. */
    public record Found(List<HiddenRun> runs, List<String> notes, java.util.Set<Integer> unchecked, boolean checked) {}
}
