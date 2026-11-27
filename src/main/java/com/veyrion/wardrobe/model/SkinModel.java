package com.veyrion.wardrobe.model;

/** Arm width of a skin. WIDE is the classic 4px arm ("Steve"); SLIM is the 3px arm ("Alex"). Stored by name. */
public enum SkinModel {
    WIDE, SLIM;

    public SkinModel other() {
        return this == WIDE ? SLIM : WIDE;
    }
}
