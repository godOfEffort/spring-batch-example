package fastcampus.spring.batch.part3;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.JpaCursorItemReader;
import org.springframework.batch.item.database.PagingQueryProvider;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.builder.JpaCursorItemReaderBuilder;
import org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.BeanPropertyRowMapper;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Configuration
public class ItemReaderConfiguration {

    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;
    private final DataSource dataSource;
    private final EntityManagerFactory entityManagerFactory;

    public ItemReaderConfiguration(JobBuilderFactory jobBuilderFactory, StepBuilderFactory stepBuilderFactory, DataSource dataSource, EntityManagerFactory entityManagerFactory) {
        this.jobBuilderFactory = jobBuilderFactory;
        this.stepBuilderFactory = stepBuilderFactory;
        this.dataSource = dataSource;
        this.entityManagerFactory = entityManagerFactory;
    }

    @Bean
    public Job itemReaderJob() throws Exception {
        return this.jobBuilderFactory.get("itemReaderJob")
                .incrementer(new RunIdIncrementer())
                .start(this.customItemReaderStep())
                .next(this.csvFileStep())
                .next(this.jdbcCursorReaderStep())
                .next(this.jdbcPagingItemStep())
                .next(this.jpaStep())
                .build();
    }

    @Bean
    public Step jdbcCursorReaderStep() throws Exception {
        return this.stepBuilderFactory.get("jdbcCursorReaderStep")
                .<Person, Person >chunk(10)
                .reader(jdbcCursorItemReader())
                .writer(itemWriter())
                .build();
    }

    @Bean
    public Step jdbcPagingItemStep() throws Exception {
        return this.stepBuilderFactory.get("jdbcPagingItemStep")
                .<Person, Person >chunk(10)
                .reader(jdbcPagingItemReader())
                .writer(itemWriter())
                .build();
    }

    @Bean
    public Step jpaStep() throws Exception {
        return stepBuilderFactory.get("jpaStep")
                .<Person, Person>chunk(10)
                .reader(this.jpaCursorItemReader())
                .writer(itemWriter())
                .build();
    }

    private JpaCursorItemReader<Person> jpaCursorItemReader() throws Exception {
        JpaCursorItemReader<Person> itemReader = new JpaCursorItemReaderBuilder<Person>()
                .name("jpaCursorItemReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("select p from Person p")
                .build();
        itemReader.afterPropertiesSet();
        return itemReader;
    }

    private JdbcPagingItemReader<Person> jdbcPagingItemReader() throws Exception {
        JdbcPagingItemReader<Person> jdbcPagingItemReader = new JdbcPagingItemReaderBuilder<Person>()
                .name("jdbcPagingItemReader")
                .pageSize(10)
                .fetchSize(10)
                .dataSource(dataSource)
                .rowMapper(((rs, rowNum) -> new Person(rs.getInt(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4))))
                .queryProvider(createQueryProvider())
                .build();

        jdbcPagingItemReader.afterPropertiesSet();
        return jdbcPagingItemReader;
    }

    private PagingQueryProvider createQueryProvider() {
        SqlPagingQueryProviderFactoryBean queryProvider = new SqlPagingQueryProviderFactoryBean();
        queryProvider.setDataSource(dataSource);
        queryProvider.setSelectClause("SELECT id, name, age, address");
        queryProvider.setFromClause("FROM person");
        queryProvider.setSortKey("id");

        try {
            return queryProvider.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create paging query providers");
        }
    }

    private JdbcCursorItemReader<Person> jdbcCursorItemReader() throws Exception {
        JdbcCursorItemReader<Person> jdbcCursorItemReader = new JdbcCursorItemReaderBuilder<Person>()
                .name("jdbcCursorItemReader")
                .dataSource(dataSource)
                .sql("select id, name, age, address from person")
                .rowMapper(((rs, rowNum) -> new Person(rs.getInt(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4))))
                .build();
        jdbcCursorItemReader.afterPropertiesSet();
        return jdbcCursorItemReader;
    }

    @Bean
    public Step csvFileStep() throws Exception {
        return stepBuilderFactory.get("csvFileStep")
                .<Person, Person>chunk(10)
                .reader(this.csvFileItemReader())
                .writer(itemWriter())
                .build();
    }

    private FlatFileItemReader<Person> csvFileItemReader() throws Exception {
        DefaultLineMapper<Person> lineMapper = new DefaultLineMapper<>();
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames("id", "name", "age", "address");
        lineMapper.setLineTokenizer(tokenizer);

        lineMapper.setFieldSetMapper(fieldSet -> {
            int id = fieldSet.readInt("id");
            String name = fieldSet.readString("name");
            String age = fieldSet.readString("age");
            String address = fieldSet.readString("address");
            return new Person(id, name, age, address);
        });

        FlatFileItemReader<Person> csvFileItemReader = new FlatFileItemReaderBuilder<Person>()
                .name("csvFileItemReader")
                .encoding("UTF-8")
                .resource(new ClassPathResource("test.csv"))
                .linesToSkip(1) // csv 헤더는 건너뛴다
                .lineMapper(lineMapper)
                .build();
        csvFileItemReader.afterPropertiesSet();

        return csvFileItemReader;
    }

    @Bean
    public Step customItemReaderStep() {
        return this.stepBuilderFactory.get("customItemReaderStep")
                .<Person, Person >chunk(10)
                .reader(new CustomItemReader<>(getItems()))
                .writer(itemWriter())
                .build();
    }

    private ItemWriter<? super Person> itemWriter() {
        return items -> log.info(items.stream().map(Person::getName)
                                .collect(Collectors.joining(", ")));
    }

    private List<Person> getItems() {
        List<Person> items = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            items.add(new Person(i +1, "test name" +i, "test age", "test address"));
        }
        return items;
    }
}
