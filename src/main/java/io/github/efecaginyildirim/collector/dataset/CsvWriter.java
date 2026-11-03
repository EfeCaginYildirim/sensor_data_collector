package io.github.efecaginyildirim.collector.dataset;

import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.persistence.DatasetRow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes dataset rows as CSV, one row at a time.
 *
 * <p>Times are written as {@code yyyy-MM-dd HH:mm:ss} in UTC, which Excel and MATLAB both parse;
 * numbers in plain notation (no exponent); unknown values as empty fields. Separator and decimal
 * mark are configurable, because French Excel expects {@code ;} and {@code ,} while MATLAB and
 * Python expect {@code ,} and {@code .}.
 */
public final class CsvWriter {

    /**
     * Separator and decimal mark.
     *
     * @param separator {@code ,}, {@code ;} or tab
     * @param decimal   {@code .} or {@code ,}; must differ from the separator
     */
    public record Format(char separator, char decimal) {

        /** Comma-separated with a decimal point: MATLAB, Python, English Excel. */
        public static final Format STANDARD = new Format(',', '.');
        /** Semicolon-separated with a decimal comma: French / Turkish Excel. */
        public static final Format EXCEL_EUROPE = new Format(';', ',');

        public Format {
            if (separator != ',' && separator != ';' && separator != '\t') {
                throw new IllegalArgumentException("separator must be comma, semicolon or tab");
            }
            if (decimal != '.' && decimal != ',') {
                throw new IllegalArgumentException("decimal mark must be '.' or ','");
            }
            if (separator == decimal) {
                throw new IllegalArgumentException("separator and decimal mark must differ");
            }
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final Writer out;
    private final Format format;
    private final StringBuilder line = new StringBuilder(256);

    /**
     * Writes the header line.
     *
     * @param out      destination; not closed by this class
     * @param format   separator and decimal mark
     * @param features value columns, in order
     */
    public CsvWriter(Writer out, Format format, List<ComputedFeature> features) {
        this.out = out;
        this.format = format;
        line.append("time_utc").append(format.separator()).append("monitor_id");
        features.forEach(f -> line.append(format.separator()).append(f.apiName()));
        writeLine();
    }

    /**
     * Writes one row.
     *
     * @param row row whose values match the header's columns
     * @throws UncheckedIOException if writing fails, e.g. the client disconnected
     */
    public void write(DatasetRow row) {
        line.append(TIME.format(row.time())).append(format.separator()).append(row.monitorId());
        for (Double v : row.values()) {
            line.append(format.separator());
            if (v != null) {
                String number = BigDecimal.valueOf(v).toPlainString();
                line.append(format.decimal() == '.' ? number : number.replace('.', format.decimal()));
            }
        }
        writeLine();
    }

    private void writeLine() {
        line.append("\r\n");
        try {
            out.write(line.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        line.setLength(0);
    }
}
