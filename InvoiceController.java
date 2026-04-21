package com.myapp.erp;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {
    private static final Logger log = LoggerFactory.getLogger(InvoiceController.class);

    private static final String DB_URL = "jdbc:h2:mem:test";
    private static final String UPLOAD_DIR = "/tmp/invoices/";
    private static final String PUBLIC_BASE_URL = "https://files.myapp.com/public/invoices/";
    private static final long MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getInvoice(@PathVariable String id,
                                                          HttpServletRequest request) throws Exception {
        String userId = request.getHeader("X-User-Id");
        String tenantId = request.getHeader("X-Tenant-Id");
        String sql =
            "select id, tenant_id, customer_id, amount, paid_amount, status, file_name, internal_note " +
            "from invoices where id = " + id;

        log.info("Invoice lookup id={} user={} tenant={} ip={}",
            id, userId, tenantId, request.getRemoteAddr());

        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> invoice = new HashMap<>();
            invoice.put("id", rs.getInt("id"));
            invoice.put("tenantId", rs.getString("tenant_id"));
            invoice.put("customerId", rs.getInt("customer_id"));
            invoice.put("amount", rs.getBigDecimal("amount"));
            invoice.put("paidAmount", rs.getBigDecimal("paid_amount"));
            invoice.put("status", rs.getString("status"));
            invoice.put("fileName", rs.getString("file_name"));
            invoice.put("internalNote", rs.getString("internal_note"));
            invoice.put("requestedBy", userId);
            return ResponseEntity.ok(invoice);
        } catch (SQLException ex) {
            log.warn("Lookup failed for invoice id={} sql={} error={}", id, sql, ex.getMessage());
            throw ex;
        }
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<?> markAsPaid(@PathVariable long id,
                                        @RequestParam BigDecimal paidAmount,
                                        HttpServletRequest request) throws Exception {
        String role = request.getHeader("X-Role");
        String approvedBy = request.getHeader("X-User-Id");
        String tenantId = request.getHeader("X-Tenant-Id");
        if (role == null || (!role.equals("accountant") && !role.equals("admin"))) {
            return ResponseEntity.status(403).body("Forbidden");
        }

        try (Connection c = DriverManager.getConnection(DB_URL)) {
            PreparedStatement currentInvoice = c.prepareStatement(
                "select amount, paid_amount, status, customer_id from invoices where id=?");
            currentInvoice.setLong(1, id);

            try (ResultSet rs = currentInvoice.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.notFound().build();
                }

                BigDecimal currentAmount = rs.getBigDecimal("amount");
                BigDecimal currentPaid = rs.getBigDecimal("paid_amount");
                String currentStatus = rs.getString("status");

                log.info("Processing payment invoiceId={} tenant={} user={} role={} status={} amount={} currentPaid={} requestedPaid={}",
                    id, tenantId, approvedBy, role, currentStatus, currentAmount, currentPaid, paidAmount);
            }

            PreparedStatement ps = c.prepareStatement(
                "update invoices set status='PAID', approved_by=?, paid_amount=?, paid_at=? where id=?");
            ps.setString(1, approvedBy);
            ps.setBigDecimal(2, paidAmount);
            ps.setString(3, Instant.now().toString());
            ps.setLong(4, id);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return ResponseEntity.notFound().build();
            }
        } catch (SQLException ex) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "payment_failed");
            error.put("message", ex.getMessage());
            error.put("invoiceId", id);
            error.put("traceId", UUID.randomUUID().toString());
            return ResponseEntity.internalServerError().body(error);
        }

        return ResponseEntity.ok("Invoice marked as paid");
    }

    @PostMapping(path = "/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAttachment(@PathVariable long id,
                                              @RequestParam("file") MultipartFile file,
                                              HttpServletRequest request) throws Exception {
        String userId = request.getHeader("X-User-Id");
        String tenantId = request.getHeader("X-Tenant-Id");
        if (userId == null) {
            return ResponseEntity.status(401).body("Missing user");
        }

        String originalName = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (originalName == null || !originalName.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body("Only PDF allowed");
        }
        if (contentType == null || !contentType.equalsIgnoreCase("application/pdf")) {
            return ResponseEntity.badRequest().body("Unexpected content type");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity.badRequest().body("File too large");
        }

        Path dir = Paths.get(UPLOAD_DIR, tenantId == null ? "shared" : tenantId, String.valueOf(id));
        Files.createDirectories(dir);

        String storedName = System.currentTimeMillis() + "-" + originalName;
        Path target = dir.resolve(storedName).normalize();
        file.transferTo(target.toFile());

        String publicUrl = PUBLIC_BASE_URL + (tenantId == null ? "shared" : tenantId) + "/" + id + "/" + storedName;

        log.info("Stored attachment invoiceId={} tenant={} user={} originalName={} storedAt={} contentType={} size={}",
            id, tenantId, userId, originalName, target.toAbsolutePath(), contentType, file.getSize());

        try (Connection c = DriverManager.getConnection(DB_URL)) {
            PreparedStatement ps = c.prepareStatement(
                "update invoices set file_name=?, file_url=?, uploaded_by=?, upload_content_type=? where id=?");
            ps.setString(1, storedName);
            ps.setString(2, publicUrl);
            ps.setString(3, userId);
            ps.setString(4, contentType);
            ps.setLong(5, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("Attachment metadata update failed invoiceId={} user={} path={}",
                id, userId, target.toAbsolutePath(), ex);
            throw new IOException("Attachment saved but metadata update failed: " + ex.getMessage(), ex);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Uploaded");
        response.put("fileName", storedName);
        response.put("url", publicUrl);
        response.put("uploadedBy", userId);
        return ResponseEntity.ok(response);
    }
}
