package de.otto.edison.jobs.service;

import de.otto.edison.jobs.domain.JobInfo;
import de.otto.edison.jobs.monitor.JobMonitor;
import de.otto.edison.jobs.repository.JobRepository;
import de.otto.edison.jobs.repository.inmem.InMemJobRepository;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.boot.actuate.metrics.GaugeService;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static de.otto.edison.testsupport.matcher.OptionalMatchers.isPresent;
import static java.time.Clock.fixed;
import static java.time.ZoneId.systemDefault;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class DefaultJobServiceTest {

    private ScheduledExecutorService executorService;

    @BeforeMethod
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        this.executorService = mock(ScheduledExecutorService.class);
        doAnswer(new RunImmediately()).when(executorService).execute(any(Runnable.class));

        when(executorService.scheduleAtFixedRate(any(Runnable.class),anyLong(),anyLong(),any(TimeUnit.class))).thenReturn(mock(ScheduledFuture.class));
    }

    @Test
    public void shouldReturnCreatedJobUri() {
        // given:
        final Clock clock = fixed(Instant.now(), systemDefault());
        final DefaultJobService jobService = new DefaultJobService(mock(JobRepository.class), mock(JobMonitor.class), emptyList(), mock(GaugeService.class), clock, executorService);
        final JobRunnable jobRunnable = mock(JobRunnable.class);
        when(jobRunnable.getJobType()).thenReturn("BAR");
        // when:
        final Optional<URI> jobUri = jobService.startAsyncJob(jobRunnable);
        // then:
        assertThat(jobUri.get().toString(), startsWith("/internal/jobs/"));
    }

    @Test
    public void shouldPersistJobs() {
        // given:
        final Clock clock = fixed(Instant.now(), systemDefault());
        final InMemJobRepository jobRepository = new InMemJobRepository();
        final DefaultJobService jobService = new DefaultJobService(jobRepository, (j)-> {}, emptyList(), mock(GaugeService.class), clock, executorService);
        final JobRunnable jobRunnable = mock(JobRunnable.class);
        when(jobRunnable.getJobType()).thenReturn("BAR");
        // when:
        final Optional<URI> jobUri = jobService.startAsyncJob(jobRunnable);
        // then:
        assertThat(jobRepository.findOne(jobUri.get()), isPresent());
    }

    @Test
    public void shouldRunJobs() {
        // given:
        final Clock clock = fixed(Instant.now(), systemDefault());
        final InMemJobRepository jobRepository = new InMemJobRepository();
        final JobRunnable jobRunnable = mock(JobRunnable.class);
        when(jobRunnable.getJobType()).thenReturn("BAR");
        final DefaultJobService jobService = new DefaultJobService(jobRepository, (j)-> {}, emptyList(), mock(GaugeService.class), clock, executorService);
        // when:
        final Optional<URI> jobUri = jobService.startAsyncJob(jobRunnable);
        // then:
        final JobInfo jobInfo = jobRepository.findOne(jobUri.get()).get();
        assertThat(jobInfo.getStopped(), isPresent());
    }

    @Test
    public void shouldRunJobsByType() {
        // given:
        final Clock clock = fixed(Instant.now(), systemDefault());
        final InMemJobRepository jobRepository = new InMemJobRepository();
        final JobRunnable jobRunnable = mock(JobRunnable.class);
        when(jobRunnable.getJobType()).thenReturn("BAR");
        final DefaultJobService jobService = new DefaultJobService(jobRepository, (j)-> {}, asList(jobRunnable), mock(GaugeService.class), clock, executorService);
        // when:
        final Optional<URI> jobUri = jobService.startAsyncJob("bar");
        // then:
        final JobInfo jobInfo = jobRepository.findOne(jobUri.get()).get();
        assertThat(jobInfo.getStopped(), isPresent());
    }

    @Test
    public void shouldNotRunSameJobsInParallel() {
        // given:
        final Clock clock = fixed(Instant.now(), systemDefault());
        final InMemJobRepository jobRepository = new InMemJobRepository();
        final JobRunnable jobRunnable = mock(JobRunnable.class);
        when(jobRunnable.getJobType()).thenReturn("BAR");
        final URI alreadyRunningJob = URI.create("/internal/jobs/barIsRunning");
        jobRepository.createOrUpdate(JobInfo.newJobInfo(alreadyRunningJob, "BAR", (j) -> {},  clock));
        final DefaultJobService jobService = new DefaultJobService(jobRepository, (j)-> {}, asList(jobRunnable), mock(GaugeService.class), clock, executorService);
        // when:
        final Optional<URI> jobUri = jobService.startAsyncJob("bar");
        // then:
        assertThat(jobUri.isPresent(), is(false));
    }

    @Test
    public void shouldRunDifferentJobsInParallel() {
        // given:
        final Clock clock = fixed(Instant.now(), systemDefault());
        final InMemJobRepository jobRepository = new InMemJobRepository();
        final JobRunnable jobRunnable = mock(JobRunnable.class);
        when(jobRunnable.getJobType()).thenReturn("FOO");
        URI alreadyRunningJob = URI.create("/internal/jobs/barIsRunning");
        jobRepository.createOrUpdate(JobInfo.newJobInfo(alreadyRunningJob, "BAR", (j) -> {},  clock));
        final DefaultJobService jobService = new DefaultJobService(jobRepository, (j)-> {}, asList(jobRunnable), mock(GaugeService.class), clock, executorService);
        // when:
        final Optional<URI> jobUri = jobService.startAsyncJob("foo");
        // then:
        assertThat(jobUri.get(), is(not(alreadyRunningJob)));
    }

    @Test
    public void shouldReportRuntime() {
        // given:
        final Clock clock = fixed(Instant.now(), systemDefault());

        final JobRunnable jobRunnable = mock(JobRunnable.class);
        when(jobRunnable.getJobType()).thenReturn("BAR");

        final GaugeService mock = mock(GaugeService.class);
        final DefaultJobService jobService = new DefaultJobService(mock(JobRepository.class), mock(JobMonitor.class), emptyList(), mock, clock, executorService);
        // when:
        jobService.startAsyncJob(jobRunnable);
        // then:
        verify(mock).submit(eq("gauge.jobs.runtime.bar"), anyLong());
    }

    private static class RunImmediately implements Answer {
        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            Runnable runnable = (Runnable) invocation.getArguments()[0];
            runnable.run();
            return null;
        }
    }
}