package de.bsnsoft.megarepo.core.format;

public class UnsupportedFormatException extends RuntimeException {

    public UnsupportedFormatException(String format) {
        super("Unsupported format: " + format);
    }
}
