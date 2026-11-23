package io.github.complydoc4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A model asks to see one masked value by its number, and only the person can allow it. */
class UnmaskTest {

    private static final String IBAN = "GB29 NWBK 6016 1331 9268 19";
    private static final String CARD = "4111 1111 1111 1111";
    private static final String TEXT = "Pay the invoice to IBAN " + IBAN + " by Friday. Card on file: " + CARD + ".";

    @TempDir
    Path tmp;

    private Gateway gateway() {
        return Gateway.create().numbered().revealLog(tmp.resolve("reveals.jsonl"));
    }

    private List<String> log() throws Exception {
        return Files.readAllLines(tmp.resolve("reveals.jsonl"));
    }

    @Test
    void eachMaskedValueCarriesANumber() {
        Gateway.Result result = gateway().filter(TEXT);
        assertTrue(result.text().contains("[#1 IBAN "), result.text());
        assertTrue(result.text().contains("[#2 Payment card number "), result.text());
        assertFalse(result.text().contains("6016"));
        assertEquals(2, result.spans().size());
        assertEquals("IBAN", result.spans().get(0).label());
        assertTrue(result.spans().get(0).context().startsWith("Pay the invoice to IBAN [#1 IBAN"));
        assertFalse(result.spans().get(1).revealable());
    }

    @Test
    void withoutNumbersTheTextIsAsItAlwaysWas() {
        Gateway.Result result = Gateway.create().revealLog(null).filter(TEXT);
        assertFalse(result.text().contains("[#"));
        assertTrue(result.spans().isEmpty());
    }

    @Test
    void aValueThePersonAllowsIsGivenAndLoggedWithoutIt() throws Exception {
        Gateway gateway = gateway();
        Gateway.Result result = gateway.filter(TEXT);
        List<Unmask.RevealRequest> asked = new ArrayList<>();
        Optional<String> value = gateway.reveal(result, 1, "to pay it", request -> asked.add(request));
        assertEquals(Optional.of(IBAN), value);
        assertTrue(asked.get(0).question().contains("Reason given: to pay it"));
        assertFalse(asked.get(0).question().contains("6016"));
        List<String> lines = log();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"decision\": \"allowed\""));
        assertFalse(lines.get(0).contains("6016"));
    }

    @Test
    void aValueThePersonDeclinesIsNotGiven() throws Exception {
        Gateway gateway = gateway();
        Gateway.Result result = gateway.filter(TEXT);
        assertEquals(Optional.empty(), gateway.reveal(result, 1, "", request -> false));
        assertTrue(log().get(0).contains("\"decision\": \"declined\""));
    }

    @Test
    void aCategoryNeverRevealedIsRefusedWithoutAsking() throws Exception {
        Gateway gateway = gateway();
        Gateway.Result result = gateway.filter(TEXT);
        Unmask.UnmaskException refused = assertThrows(Unmask.UnmaskException.class,
                () -> gateway.reveal(result, 2, "", request -> {
                    throw new AssertionError("the person must not be asked");
                }));
        assertTrue(refused.getMessage().contains("never revealed"));
        assertTrue(log().get(0).contains("\"decision\": \"refused\""));
    }

    @Test
    void aNumberThatIsNotThereIsRefused() {
        Gateway gateway = gateway();
        Gateway.Result result = gateway.filter(TEXT);
        Unmask.UnmaskException refused = assertThrows(Unmask.UnmaskException.class,
                () -> gateway.reveal(result, 9, "", request -> true));
        assertTrue(refused.getMessage().contains("from #1 to #2"));
    }

    @Test
    void aResultFromAnotherGatewayCannotBeRevealed() {
        Gateway.Result other = Gateway.create().numbered().revealLog(null).filter(TEXT);
        assertThrows(Unmask.UnmaskException.class, () -> gateway().reveal(other, 1, "", request -> true));
    }
}
