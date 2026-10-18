package dev.chaya.api.stitch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxRooms the most rooms one stitch combines. ROOM_REGISTRATION registers every pair of them (docs/multi-room.md),
 *                 so its work grows with the square of this; 12 rooms are 66 pairwise registrations (default 12)
 */
@ConfigurationProperties("chaya.stitch")
public record StitchProperties(int maxRooms) {

    public StitchProperties {
        if (maxRooms == 0) {
            maxRooms = 12;
        }
        if (maxRooms < 2 || maxRooms > 50) {
            throw new IllegalStateException("chaya.stitch.max-rooms must be between 2 and 50");
        }
    }
}
