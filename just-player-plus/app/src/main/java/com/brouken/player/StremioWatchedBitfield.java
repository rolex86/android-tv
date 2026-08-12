package com.brouken.player;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Pure-Java implementation of Stremio's anchored watched bitfield. */
final class StremioWatchedBitfield {
    private static final int MAX_VIDEOS = 10_000;
    private static final int MAX_PACKED_BYTES = 64 * 1024;
    private static final char[] BASE64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static final class Video {
        final String id;
        final int season;
        final int episode;
        final String released;

        Video(String id, int season, int episode, @Nullable String released) {
            this.id = id;
            this.season = season;
            this.episode = episode;
            this.released = released == null ? "" : released;
        }
    }

    private StremioWatchedBitfield() {
    }

    static List<String> orderedVideoIds(JSONObject metaResponse) throws IOException {
        JSONObject meta = metaResponse.optJSONObject("meta");
        JSONArray videos = meta == null ? null : meta.optJSONArray("videos");
        if (videos == null || videos.length() == 0 || videos.length() > MAX_VIDEOS) {
            throw new IOException("Cinemeta returned an invalid video list");
        }
        List<Video> parsed = new ArrayList<>();
        Set<String> uniqueIds = new HashSet<>();
        for (int index = 0; index < videos.length(); index++) {
            JSONObject video = videos.optJSONObject(index);
            if (video == null) {
                continue;
            }
            String id = video.optString("id", "").trim();
            if (id.isEmpty()) {
                continue;
            }
            if (!uniqueIds.add(id)) {
                throw new IOException("Cinemeta returned duplicate video IDs");
            }
            parsed.add(new Video(
                    id,
                    video.has("season") ? video.optInt("season", Integer.MIN_VALUE)
                            : Integer.MIN_VALUE,
                    video.has("episode") ? video.optInt("episode", Integer.MIN_VALUE)
                            : Integer.MIN_VALUE,
                    video.optString("released", "")));
        }
        Collections.sort(parsed, Comparator
                .comparingInt((Video video) -> video.season)
                .thenComparingInt(video -> video.episode)
                .thenComparing(video -> video.released));
        List<String> ids = new ArrayList<>(parsed.size());
        for (Video video : parsed) {
            ids.add(video.id);
        }
        return ids;
    }

    static boolean[] decode(@Nullable String serialized, List<String> videoIds)
            throws IOException {
        boolean[] flags = new boolean[videoIds.size()];
        if (serialized == null || serialized.trim().isEmpty()) {
            return flags;
        }
        int payloadSeparator = serialized.lastIndexOf(':');
        int lengthSeparator = payloadSeparator <= 0
                ? -1 : serialized.lastIndexOf(':', payloadSeparator - 1);
        if (lengthSeparator <= 0 || payloadSeparator == serialized.length() - 1) {
            throw new IOException("Malformed Stremio watched field");
        }
        String anchorVideo = serialized.substring(0, lengthSeparator);
        final int anchorLength;
        try {
            anchorLength = Integer.parseInt(
                    serialized.substring(lengthSeparator + 1, payloadSeparator));
        } catch (NumberFormatException error) {
            throw new IOException("Malformed Stremio watched anchor", error);
        }
        if (anchorLength < 0 || anchorLength > MAX_VIDEOS) {
            throw new IOException("Stremio watched anchor is out of bounds");
        }
        int anchorIndex = videoIds.indexOf(anchorVideo);
        if (anchorIndex < 0) {
            return flags;
        }
        byte[] values = inflate(decodeBase64(serialized.substring(payloadSeparator + 1)));
        int offset = anchorLength - anchorIndex - 1;
        for (int index = 0; index < flags.length; index++) {
            int previousIndex = index + offset;
            flags[index] = previousIndex >= 0 && getBit(values, previousIndex);
        }
        return flags;
    }

    static String setWatched(
            @Nullable String serialized,
            List<String> videoIds,
            String targetVideoId,
            boolean watched) throws IOException {
        int targetIndex = videoIds.indexOf(targetVideoId);
        if (targetIndex < 0) {
            throw new IOException("Target episode is absent from Cinemeta ordering");
        }
        boolean[] flags = decode(serialized, videoIds);
        flags[targetIndex] = watched;
        return encode(flags, videoIds);
    }

    static boolean isWatched(
            @Nullable String serialized,
            List<String> videoIds,
            String targetVideoId) throws IOException {
        int index = videoIds.indexOf(targetVideoId);
        return index >= 0 && decode(serialized, videoIds)[index];
    }

    static String encode(boolean[] flags, List<String> videoIds) throws IOException {
        if (flags.length != videoIds.size()) {
            throw new IOException("Watched flags do not match the video list");
        }
        byte[] values = new byte[(flags.length + 7) / 8];
        int lastWatched = 0;
        for (int index = 0; index < flags.length; index++) {
            if (flags[index]) {
                values[index / 8] |= (byte) (1 << (index % 8));
                lastWatched = index;
            }
        }
        String anchor = videoIds.isEmpty() ? "undefined" : videoIds.get(lastWatched);
        return anchor + ":" + (lastWatched + 1) + ":" + encodeBase64(deflate(values));
    }

    private static boolean getBit(byte[] values, int index) {
        int byteIndex = index / 8;
        return byteIndex < values.length
                && (((values[byteIndex] & 0xff) >> (index % 8)) & 1) != 0;
    }

    private static byte[] deflate(byte[] input) throws IOException {
        Deflater deflater = new Deflater(6);
        deflater.setInput(input);
        deflater.finish();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count <= 0) {
                    throw new IOException("Could not compress watched field");
                }
                output.write(buffer, 0, count);
                if (output.size() > MAX_PACKED_BYTES) {
                    throw new IOException("Watched field is too large");
                }
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] input) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(input);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(buffer);
                } catch (DataFormatException error) {
                    throw new IOException("Malformed compressed watched field", error);
                }
                if (count > 0) {
                    output.write(buffer, 0, count);
                    if (output.size() > MAX_PACKED_BYTES) {
                        throw new IOException("Watched field is too large");
                    }
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw new IOException("Incomplete compressed watched field");
                }
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static String encodeBase64(byte[] input) {
        StringBuilder output = new StringBuilder(((input.length + 2) / 3) * 4);
        for (int index = 0; index < input.length; index += 3) {
            int first = input[index] & 0xff;
            int second = index + 1 < input.length ? input[index + 1] & 0xff : 0;
            int third = index + 2 < input.length ? input[index + 2] & 0xff : 0;
            int value = (first << 16) | (second << 8) | third;
            output.append(BASE64[(value >> 18) & 63]);
            output.append(BASE64[(value >> 12) & 63]);
            output.append(index + 1 < input.length ? BASE64[(value >> 6) & 63] : '=');
            output.append(index + 2 < input.length ? BASE64[value & 63] : '=');
        }
        return output.toString();
    }

    private static byte[] decodeBase64(String input) throws IOException {
        String value = input.trim();
        if (value.length() % 4 != 0 || value.length() > MAX_PACKED_BYTES * 2) {
            throw new IOException("Malformed base64 watched field");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(value.length() * 3 / 4);
        for (int index = 0; index < value.length(); index += 4) {
            int a = base64Value(value.charAt(index));
            int b = base64Value(value.charAt(index + 1));
            char thirdChar = value.charAt(index + 2);
            char fourthChar = value.charAt(index + 3);
            int c = thirdChar == '=' ? 0 : base64Value(thirdChar);
            int d = fourthChar == '=' ? 0 : base64Value(fourthChar);
            if (a < 0 || b < 0 || c < 0 || d < 0
                    || (thirdChar == '=' && fourthChar != '=')
                    || (index + 4 < value.length()
                    && (thirdChar == '=' || fourthChar == '='))) {
                throw new IOException("Malformed base64 watched field");
            }
            int decoded = (a << 18) | (b << 12) | (c << 6) | d;
            output.write((decoded >> 16) & 0xff);
            if (thirdChar != '=') {
                output.write((decoded >> 8) & 0xff);
            }
            if (fourthChar != '=') {
                output.write(decoded & 0xff);
            }
        }
        return output.toByteArray();
    }

    private static int base64Value(char value) {
        if (value >= 'A' && value <= 'Z') return value - 'A';
        if (value >= 'a' && value <= 'z') return value - 'a' + 26;
        if (value >= '0' && value <= '9') return value - '0' + 52;
        if (value == '+') return 62;
        if (value == '/') return 63;
        return -1;
    }
}
