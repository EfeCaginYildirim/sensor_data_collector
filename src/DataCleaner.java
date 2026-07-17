import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Veritabanı temizleme sınıfı - eski verileri silmek için
 */
public class DataCleaner {
    
    /**
     * Tüm verileri sil
     */
    public static void clearAllData() {
        try {
            Connection conn = DatabaseConnectionPool.getConnection();
            try {
                Statement stmt = conn.createStatement();
                
                System.out.println("🗑️ Tüm veriler siliniyor...");
                int deletedRows = stmt.executeUpdate("DELETE FROM dbo.Motor_Data");
                
                conn.commit();
                System.out.println("✅ " + deletedRows + " kayıt silindi!");
                
            } finally {
                DatabaseConnectionPool.releaseConnection(conn);
            }
            
        } catch (Exception e) {
            System.out.println("❌ Veri silme hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Belirli tarihten eski verileri sil
     */
    public static void clearOldData(String olderThanDate) {
        try {
            Connection conn = DatabaseConnectionPool.getConnection();
            try {
                PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM dbo.Motor_Data WHERE basicFeature_time < ? OR computedFeature_timestamp < ?"
                );
                
                ps.setString(1, olderThanDate);
                ps.setString(2, olderThanDate);
                
                System.out.println("🗑️ " + olderThanDate + " tarihinden eski veriler siliniyor...");
                int deletedRows = ps.executeUpdate();
                
                conn.commit();
                System.out.println("✅ " + deletedRows + " eski kayıt silindi!");
                
            } finally {
                DatabaseConnectionPool.releaseConnection(conn);
            }
            
        } catch (Exception e) {
            System.out.println("❌ Eski veri silme hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Belirli monitörün verilerini sil
     */
    public static void clearMonitorData(int monitorId) {
        try {
            Connection conn = DatabaseConnectionPool.getConnection();
            try {
                PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM dbo.Motor_Data WHERE monitor_id = ?"
                );
                
                ps.setInt(1, monitorId);
                
                System.out.println("🗑️ Monitör " + monitorId + " verileri siliniyor...");
                int deletedRows = ps.executeUpdate();
                
                conn.commit();
                System.out.println("✅ " + deletedRows + " kayıt silindi!");
                
            } finally {
                DatabaseConnectionPool.releaseConnection(conn);
            }
            
        } catch (Exception e) {
            System.out.println("❌ Monitör veri silme hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Veritabanı istatistiklerini göster
     */
    public static void showDatabaseStats() {
        try {
            Connection conn = DatabaseConnectionPool.getConnection();
            try {
                Statement stmt = conn.createStatement();
                
                // Toplam kayıt sayısı
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as total FROM dbo.Motor_Data");
                if (rs.next()) {
                    System.out.println("📊 Toplam kayıt sayısı: " + rs.getInt("total"));
                }
                
                // Monitör bazında kayıt sayıları
                rs = stmt.executeQuery(
                    "SELECT monitor_id, COUNT(*) as count FROM dbo.Motor_Data GROUP BY monitor_id ORDER BY monitor_id"
                );
                System.out.println("📈 Monitör bazında kayıt sayıları:");
                while (rs.next()) {
                    System.out.println("  Monitör " + rs.getInt("monitor_id") + ": " + rs.getInt("count") + " kayıt");
                }
                
                // Tarih aralığı
                rs = stmt.executeQuery(
                    "SELECT MIN(basicFeature_time) as min_date, MAX(basicFeature_time) as max_date FROM dbo.Motor_Data"
                );
                if (rs.next()) {
                    System.out.println("📅 Veri tarih aralığı: " + rs.getString("min_date") + " - " + rs.getString("max_date"));
                }
                
            } finally {
                DatabaseConnectionPool.releaseConnection(conn);
            }
            
        } catch (Exception e) {
            System.out.println("❌ İstatistik hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Test main method
     */
    public static void main(String[] args) {
        System.out.println("🧹 VERİTABANI TEMİZLEME ARACI");
        System.out.println("Tarih: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")));
        
        if (args.length == 0) {
            System.out.println("\nKullanım:");
            System.out.println("  java DataCleaner stats                    - İstatistikleri göster");
            System.out.println("  java DataCleaner clear                    - Tüm verileri sil");
            System.out.println("  java DataCleaner clear-old 2024-01-01     - Eski verileri sil");
            System.out.println("  java DataCleaner clear-monitor <monitorId>  - Monitör verilerini sil");
            return;
        }
        
        String command = args[0].toLowerCase();
        
        switch (command) {
            case "stats":
                showDatabaseStats();
                break;
                
            case "clear":
                System.out.println("⚠️ TÜM VERİLER SİLİNECEK! Devam etmek istiyor musunuz? (y/N)");
                // Burada kullanıcı onayı alınabilir
                clearAllData();
                break;
                
            case "clear-old":
                if (args.length < 2) {
                    System.out.println("❌ Tarih belirtilmedi!");
                    return;
                }
                clearOldData(args[1]);
                break;
                
            case "clear-monitor":
                if (args.length < 2) {
                    System.out.println("❌ Monitör ID belirtilmedi!");
                    return;
                }
                try {
                    int monitorId = Integer.parseInt(args[1]);
                    clearMonitorData(monitorId);
                } catch (NumberFormatException e) {
                    System.out.println("❌ Geçersiz monitör ID!");
                }
                break;
                
            default:
                System.out.println("❌ Bilinmeyen komut: " + command);
        }
    }
}

