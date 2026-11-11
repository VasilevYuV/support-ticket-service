package ru.vasilev.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import ru.vasilev.support.config.TimeConfiguration
import java.time.Clock
import java.time.ZoneOffset

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationWiringTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun javaApplicationLoadsKotlinConfiguration() {
        assertThat(context.getBean(SupportApplication::class.java)).isNotNull()
        assertThat(context.getBean(TimeConfiguration::class.java)).isNotNull()
        assertThat(context.getBean(Clock::class.java).zone).isEqualTo(ZoneOffset.UTC)
    }
}
