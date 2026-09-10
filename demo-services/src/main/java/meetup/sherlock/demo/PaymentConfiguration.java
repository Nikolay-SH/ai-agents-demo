package meetup.sherlock.demo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "demo.role", havingValue = "payment")
class PaymentConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(@Value("${demo.jdbc-url}") String url,
                               @Value("${demo.db-user}") String user,
                               @Value("${demo.db-password}") String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName("payment-pool");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(1500);
        config.setValidationTimeout(1000);
        config.setInitializationFailTimeout(5000);
        return new HikariDataSource(config);
    }
    @Bean JdbcTemplate paymentJdbc(HikariDataSource pool) {
        var jdbc = new JdbcTemplate(pool);
        jdbc.setQueryTimeout(2);
        jdbc.execute("CREATE TABLE IF NOT EXISTS payments (id UUID PRIMARY KEY, amount_cents INTEGER NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)");
        return jdbc;
    }
}
