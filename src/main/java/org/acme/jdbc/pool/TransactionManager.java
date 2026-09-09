package org.acme.jdbc.pool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事务管理器：为每个事务借用并持有独立连接（autoCommit=false），
 * 支持 begin / commit / rollback 生命周期管理。
 */
@ApplicationScoped
public class TransactionManager {

    private static final Logger LOG = Logger.getLogger(TransactionManager.class);

    @Inject
    ConnectionPoolManager poolManager;

    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();

    public String begin(String connectionId) throws Exception {
        if (connectionId == null || connectionId.isEmpty()) {
            throw new IllegalArgumentException("connectionId is required");
        }
        Connection connection = poolManager.getConnection(connectionId);
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new SQLException("Failed to disable auto-commit for transaction", e);
        }
        String txId = UUID.randomUUID().toString();
        transactions.put(txId, new Transaction(connectionId, connection));
        LOG.infof("Transaction started: %s (connection: %s)", txId, connectionId);
        return txId;
    }

    /**
     * 获取事务绑定的连接；事务不存在返回 null。
     * 调用方不得关闭该连接（连接生命周期由事务管理器管理）。
     */
    public Connection getConnection(String transactionId) {
        Transaction tx = transactions.get(transactionId);
        return tx == null ? null : tx.connection;
    }

    public void commit(String transactionId) throws Exception {
        Transaction tx = take(transactionId);
        if (tx == null) {
            throw new IllegalArgumentException("Transaction not found: " + transactionId);
        }
        try {
            tx.connection.commit();
            LOG.infof("Transaction committed: %s", transactionId);
        } catch (SQLException e) {
            rollbackQuietly(tx);
            throw new SQLException("Failed to commit transaction: " + transactionId, e);
        } finally {
            closeQuietly(tx);
        }
    }

    public void rollback(String transactionId) throws Exception {
        Transaction tx = take(transactionId);
        if (tx == null) {
            throw new IllegalArgumentException("Transaction not found: " + transactionId);
        }
        try {
            tx.connection.rollback();
            LOG.infof("Transaction rolled back: %s", transactionId);
        } finally {
            closeQuietly(tx);
        }
    }

    private Transaction take(String transactionId) {
        return transactions.remove(transactionId);
    }

    private void rollbackQuietly(Transaction tx) {
        try {
            tx.connection.rollback();
        } catch (SQLException ignored) {
            // ignore
        }
    }

    private void closeQuietly(Transaction tx) {
        closeQuietly(tx.connection);
    }

    private void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.debugf("Failed to close connection: %s", e.getMessage());
        }
    }

    private static class Transaction {
        final String connectionId;
        final Connection connection;

        Transaction(String connectionId, Connection connection) {
            this.connectionId = connectionId;
            this.connection = connection;
        }
    }
}
