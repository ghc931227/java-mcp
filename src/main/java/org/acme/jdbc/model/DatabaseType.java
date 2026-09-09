package org.acme.jdbc.model;

public enum DatabaseType {
    MYSQL("com.mysql.cj.jdbc.Driver", "jdbc:mysql://{host}:{port}/{database}"),
    POSTGRESQL("org.postgresql.Driver", "jdbc:postgresql://{host}:{port}/{database}"),
    ORACLE("oracle.jdbc.OracleDriver", "jdbc:oracle:thin:@{host}:{port}:{database}"),
    SQLSERVER("com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver://{host}:{port};databaseName={database}"),
    H2("org.h2.Driver", "jdbc:h2:file:./data/h2/{database}"),
    SQLITE("org.sqlite.JDBC", "jdbc:sqlite:{database}"),
    MARIADB("org.mariadb.jdbc.Driver", "jdbc:mariadb://{host}:{port}/{database}"),
    KINGBASE("com.kingbase8.Driver", "jdbc:kingbase8://{host}:{port}/{database}"),
    DM("dm.jdbc.driver.DmDriver", "jdbc:dm://{host}:{port}"),
    CUSTOM("", "");
    
    private final String driverClassName;
    private final String urlTemplate;
    
    DatabaseType(String driverClassName, String urlTemplate) {
        this.driverClassName = driverClassName;
        this.urlTemplate = urlTemplate;
    }
    
    public String getDriverClassName() {
        return driverClassName;
    }
    
    public String getUrlTemplate() {
        return urlTemplate;
    }
    
    public String buildUrl(String host, int port, String database) {
        String url = urlTemplate;
        if (host != null) {
            url = url.replace("{host}", host);
        }
        if (database != null) {
            url = url.replace("{database}", database);
        }
        return url.replace("{port}", String.valueOf(port));
    }
}
