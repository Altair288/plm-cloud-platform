package com.plm.common.version.domain;

import java.io.Serializable;
import java.util.UUID;

public class CategoryHierarchyId implements Serializable {
    private UUID ancestorDef;
    private UUID descendantDef;

    public CategoryHierarchyId() {}
    public CategoryHierarchyId(UUID a, UUID d) { this.ancestorDef = a; this.descendantDef = d; }
    public UUID getAncestorDef() { return ancestorDef; }
    public void setAncestorDef(UUID ancestorDef) { this.ancestorDef = ancestorDef; }
    public UUID getDescendantDef() { return descendantDef; }
    public void setDescendantDef(UUID descendantDef) { this.descendantDef = descendantDef; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CategoryHierarchyId other)) return false;
        return java.util.Objects.equals(ancestorDef, other.ancestorDef) && java.util.Objects.equals(descendantDef, other.descendantDef);
    }

    @Override
    public int hashCode() { return java.util.Objects.hash(ancestorDef, descendantDef); }
}
