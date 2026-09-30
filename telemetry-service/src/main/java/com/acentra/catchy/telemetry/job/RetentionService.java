package com.acentra.catchy.telemetry.job;

import com.acentra.catchy.telemetry.config.CatchyProperties;
import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.EventRepository;
import com.acentra.catchy.telemetry.domain.SnapshotRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the database bounded: the newest N events per application, and snapshot history only for the last few hours
 * (the row defining an instance's latest state is never pruned).
 */
@Service
public class RetentionService {
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final EventRepository events;
    private final SnapshotRepository snapshots;
    private final ApplicationRepository applications;
    private final CatchyProperties props;
    private final Clock clock;

    public RetentionService(EventRepository events, SnapshotRepository snapshots, ApplicationRepository applications,
                            CatchyProperties props, Clock clock) {
        this.events = events;
        this.snapshots = snapshots;
        this.applications = applications;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public void prune() {
        int cap = props.retention().eventsPerApplication();
        int deletedEvents = 0;
        for (Long appId : applications.findAllByOrderById().stream().map(a -> a.id).toList()) {
            List<Long> cutoff = events.findIdsNewestFirst(appId, PageRequest.of(cap, 1));
            if (!cutoff.isEmpty()) deletedEvents += events.deleteUpTo(appId, cutoff.get(0));
        }
        Instant snapshotCutoff = Times.now(clock).minus(props.retention().snapshotMaxAge());
        int deletedSnapshots = snapshots.deleteHistoryOlderThan(snapshotCutoff);
        if (deletedEvents > 0 || deletedSnapshots > 0) {
            log.info("Retention pruned {} events and {} snapshots", deletedEvents, deletedSnapshots);
        }
    }
}
