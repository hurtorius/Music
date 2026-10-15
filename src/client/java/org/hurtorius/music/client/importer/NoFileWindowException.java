package org.hurtorius.music.client.importer;

import java.io.IOException;

/**
 * The system has no program to show a file window with: on Linux, neither zenity nor kdialog. Inside
 * a Flatpak sandbox (the usual way to run the game on a Steam Deck) the player cannot add one, so
 * the screen offers ways that need no file window instead.
 */
public final class NoFileWindowException extends IOException {
    private final boolean sandboxed;

    NoFileWindowException(boolean sandboxed) {
        super(sandboxed ? "No file dialog program inside the Flatpak sandbox" : "This system has no file dialog program (zenity or kdialog)");
        this.sandboxed = sandboxed;
    }

    /** True inside a Flatpak sandbox, where installing a dialog program does not help the game. */
    public boolean sandboxed() {
        return sandboxed;
    }
}
