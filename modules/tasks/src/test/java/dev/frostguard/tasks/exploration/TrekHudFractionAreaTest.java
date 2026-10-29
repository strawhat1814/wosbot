package dev.frostguard.tasks.exploration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.SizeData;

class TrekHudFractionAreaTest {

    @Test
    void placesOcrBandDirectlyUnderMatchedIcon() {
        ImageSearchResultData icon = new ImageSearchResultData();
        icon.setFound(true);
        icon.setHitX(640);
        icon.setHitY(400);
        icon.setTemplateSize(new SizeData(45, 48));

        AreaData fraction = TundraTrekAutoRoutine.fractionAreaBelowIcon(icon);
        assertNotNull(fraction);
        // Matched area top-left is center - half size: (640-22, 400-24) = (618, 376)
        // bottom-right y = 376+48-1 = 423 → fraction starts at 424
        assertEquals(618 - 28, fraction.topLeft().getX());
        assertEquals(424, fraction.topLeft().getY());
        assertEquals(40, fraction.bottomRight().getY() - fraction.topLeft().getY());
    }
}
