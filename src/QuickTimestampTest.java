import java.time.LocalDateTime;

public class QuickTimestampTest {
    public static void main(String[] args) {
        System.out.println("🔍 Hızlı Timestamp Test");
        System.out.println("========================");
        
        // Test null timestamp
        System.out.println("Test 1: Null timestamp");
        LocalDateTime result1 = TimestampValidator.parseTimestamp(null);
        System.out.println("  Result: " + result1);
        System.out.println();
        
        // Test empty string
        System.out.println("Test 2: Empty string");
        LocalDateTime result2 = TimestampValidator.parseTimestamp("");
        System.out.println("  Result: " + result2);
        System.out.println();
        
        // Test whitespace string
        System.out.println("Test 3: Whitespace string");
        LocalDateTime result3 = TimestampValidator.parseTimestamp("   ");
        System.out.println("  Result: " + result3);
        System.out.println();
        
        // Test valid timestamp
        System.out.println("Test 4: Valid timestamp");
        LocalDateTime result4 = TimestampValidator.parseTimestamp("2025-01-15T10:30:00Z");
        System.out.println("  Result: " + result4);
        System.out.println();
        
        // Test invalid timestamp
        System.out.println("Test 5: Invalid timestamp");
        LocalDateTime result5 = TimestampValidator.parseTimestamp("invalid-timestamp");
        System.out.println("  Result: " + result5);
        System.out.println();
        
        // Test Unix epoch timestamp
        System.out.println("Test 6: Unix epoch timestamp");
        LocalDateTime result6 = TimestampValidator.parseTimestamp("1754525884000");
        System.out.println("  Result: " + result6);
        System.out.println();
        
        System.out.println("✅ Test tamamlandı!");
    }
}

