package com.example.smartttexam.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.example.smartttexam.oj.OjModels.*;

final class OjFingerprints {
    private OjFingerprints() { }
    /** Reject malformed UTF-16 instead of replacing it during UTF-8 encoding. Never normalize source. */
    static byte[] utf8(String text) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("Text contains an invalid UTF-16 surrogate sequence", ex);
        }
    }
    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(utf8(text));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String packed(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(value.length()).append(':').append(value);
        return result.toString();
    }
    static String id(Owner owner, String requestId) {
        return sha256(packed(owner.getStudentId(), owner.getCourseId(), owner.getClassroomId(), requestId));
    }
    static String content(Owner owner, Problem problem, String source, String language) throws IOException {
        return sha256(packed(new ObjectMapper().writeValueAsString(owner),
                new ObjectMapper().writeValueAsString(problem), source, language));
    }
}
