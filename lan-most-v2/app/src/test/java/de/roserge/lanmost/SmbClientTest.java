package de.roserge.lanmost;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmbClientTest {
    @Test public void cyrillicFilenameStaysIntact() {
        assertEquals("Книга 01.fb2", SmbClient.cleanFilename("Книга 01.fb2"));
    }

    @Test public void windowsReservedCharactersReplaced() {
        assertEquals("song_test_.mp3", SmbClient.cleanFilename("song:test?.mp3"));
    }

    @Test public void attemptedTraversalIsFlattened() {
        assertEquals(".._secret.txt", SmbClient.cleanFilename("../secret.txt"));
    }

    @Test(expected=IllegalArgumentException.class)
    public void dotDotIsRejected() {
        SmbClient.cleanFilename("..");
    }
}
