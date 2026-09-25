package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioFailure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioResolutionBudgetTest {

    @Test
    void acceptsTheExactStepLimitAndRejectsTheNextStep() throws Exception {
        AudioResolutionBudget budget = new AudioResolutionBudget(limits(3, 2));

        budget.consumeSteps(3);

        assertEquals(0, budget.remainingSteps());
        RadioSourceException failure = assertThrows(RadioSourceException.class,
                () -> budget.consumeSteps(1));
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, failure.code());
        assertFalse(failure.recoverable());
        assertEquals(0, budget.remainingSteps());
    }

    @Test
    void acceptsTheExactAggregateEntryLimitAndRejectsTheNextEntry() throws Exception {
        AudioResolutionBudget budget = new AudioResolutionBudget(limits(3, 2));

        budget.consumeEntries(1);
        budget.consumeEntries(1);

        RadioSourceException failure = assertThrows(RadioSourceException.class,
                () -> budget.consumeEntries(1));
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, failure.code());
        assertFalse(failure.recoverable());
    }

    private static AudioResolveLimits limits(int steps, int entries) {
        return new AudioResolveLimits(4, 64, entries, 32, 1, steps);
    }
}
