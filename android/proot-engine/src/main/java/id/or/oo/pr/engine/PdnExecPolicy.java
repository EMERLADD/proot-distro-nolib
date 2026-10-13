package id.or.oo.pr.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Optional host policy, checked by PRoot for traced execs, including descendants. */
public final class PdnExecPolicy {
    private final List<PdnExecRule> rules;
    public PdnExecPolicy(List<PdnExecRule> rules) {
        ArrayList<PdnExecRule> copy = new ArrayList<>(Objects.requireNonNull(rules));
        if (copy.size() > 128) throw new IllegalArgumentException("At most 128 execution rules are allowed");
        for (PdnExecRule rule : copy) Objects.requireNonNull(rule);
        this.rules = Collections.unmodifiableList(copy);
    }
    public List<PdnExecRule> getRules() { return rules; }
}
