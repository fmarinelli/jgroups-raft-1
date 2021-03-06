package org.jgroups.protocols.raft;

import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.Message;
import org.jgroups.annotations.MBean;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.annotations.Property;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.stack.Protocol;
import org.jgroups.util.MessageBatch;
import org.jgroups.util.TimeScheduler;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Performs leader election. Starts an election timer on connect and starts an election when the timer goes off and
 * no heartbeats have been received. Runs a heartbeat task when leader.
 * @author Bela Ban
 * @since  0.1
 */
@MBean(description="Protocol performing leader election according to the RAFT paper")
public class ELECTION extends Protocol {
    // when moving to JGroups -> add to jg-protocol-ids.xml
    protected static final short ELECTION_ID    = 1025;

    // When moving to JGroups -> add to jg-magic-map.xml
    protected static final short VOTE_REQ       = 3000;
    protected static final short VOTE_RSP       = 3001;
    protected static final short HEARTBEAT_REQ  = 3002;


    static {
        ClassConfigurator.addProtocol(ELECTION_ID, ELECTION.class);
        ClassConfigurator.add(VOTE_REQ,      VoteRequest.class);
        ClassConfigurator.add(VOTE_RSP,      VoteResponse.class);
        ClassConfigurator.add(HEARTBEAT_REQ, HeartbeatRequest.class);
    }

    @Property(description="Interval (in ms) at which a leader sends out heartbeats")
    protected long              heartbeat_interval=30;

    @Property(description="Min election interval (ms)")
    protected long              election_min_interval=150;

    @Property(description="Max election interval (ms). The actual election interval is computed as a random value in " +
      "range [election_min_interval..election_max_interval]")
    protected long              election_max_interval=300;


    /** The address of the candidate this node voted for in the current term */
    protected Address           voted_for;

    /** Votes collected for me in the current term (if candidate) */
    @ManagedAttribute(description="Number of votes this candidate received in the current term")
    protected int               current_votes;

    @ManagedAttribute(description="No election will ever be started if true; this node will always be a follower. " +
      "Used only for testing and may get removed. Don't use !")
    protected boolean           no_elections;


    /** Whether a heartbeat has been received before this election timeout kicked in. If false, the follower becomes
     * candidate and starts a new election */
    protected volatile boolean  heartbeat_received=true;

    protected RAFT              raft; // direct ref instead of events
    protected Address           local_addr;
    protected TimeScheduler     timer;
    protected Future<?>         election_task;
    protected Future<?>         heartbeat_task;
    protected Role              role=Role.Follower;

    @ManagedAttribute(description="The current role")
    public String role() {return role.toString();}

    @ManagedAttribute(description="Is the heartbeat task running")
    public synchronized boolean isHeartbeatTaskRunning() {
        return heartbeat_task != null && !heartbeat_task.isDone();
    }

    @ManagedAttribute(description="Is the election ttimer running")
    public synchronized boolean isElectionTimerRunning() {return election_task != null && !election_task.isDone();}

    public void init() throws Exception {
        super.init();
        if(election_min_interval >= election_max_interval)
            throw new Exception("election_min_interval (" + election_min_interval + ") needs to be smaller than " +
                                  "election_max_interval (" + election_max_interval + ")");
        timer=getTransport().getTimer();
        raft=findProtocol(RAFT.class);
    }


    public Object down(Event evt) {
        switch(evt.getType()) {
            case Event.CONNECT:
            case Event.CONNECT_USE_FLUSH:
            case Event.CONNECT_WITH_STATE_TRANSFER:
            case Event.CONNECT_WITH_STATE_TRANSFER_USE_FLUSH:
                Object retval=down_prot.down(evt); // connect first
                startElectionTimer();
                return retval;
            case Event.DISCONNECT:
                changeRole(Role.Follower);
                stopElectionTimer();
                break;
            case Event.SET_LOCAL_ADDRESS:
                local_addr=(Address)evt.getArg();
                break;
        }
        return down_prot.down(evt);
    }


    public Object up(Event evt) {
        switch(evt.getType()) {
            case Event.MSG:
                Message msg=(Message)evt.getArg();
                RaftHeader hdr=(RaftHeader)msg.getHeader(id);
                if(hdr == null)
                    break;
                handleEvent(msg, hdr);
                return null;
        }
        return up_prot.up(evt);
    }


    public void up(MessageBatch batch) {
        for(Message msg: batch) {
            RaftHeader hdr=(RaftHeader)msg.getHeader(id);
            if(hdr != null) {
                batch.remove(msg);
                handleEvent(msg, hdr);
            }
        }
        if(!batch.isEmpty())
            up_prot.up(batch);
    }


    protected void handleEvent(Message msg, RaftHeader hdr) {
        // log.trace("%s: received %s from %s", local_addr, hdr, msg.src());
        if(hdr instanceof HeartbeatRequest) {
            HeartbeatRequest hb=(HeartbeatRequest)hdr;
            handleHeartbeat(hb.term(), hb.leader);
        }
        else if(hdr instanceof VoteRequest) {
            VoteRequest header=(VoteRequest)hdr;
            handleVoteRequest(msg.src(), header.term());
        }
        else if(hdr instanceof VoteResponse) {
            VoteResponse rsp=(VoteResponse)hdr;
            handleVoteResponse(rsp.term());
        }
    }


    protected synchronized void handleHeartbeat(int term, Address leader) {
        if(local_addr != null && local_addr.equals(leader))
            return;
        heartbeatReceived(true);
        if(role != Role.Follower || raft.updateTermAndLeader(term, leader)) {
            changeRole(Role.Follower);
            voted_for=null;
        }
    }

    protected void handleVoteRequest(Address sender, int term) {
        if(local_addr != null && local_addr.equals(sender))
            return;
        boolean send_vote_rsp=false;
        synchronized(this) {
            if(term > raft.current_term) {
                raft.currentTerm(term);
                voted_for=null;
            }
            if(voteFor(sender))
                send_vote_rsp=true;
        }
        if(send_vote_rsp)
            sendVoteResponse(sender, term); // raft.current_term);
    }

    protected synchronized void handleVoteResponse(int term) {
        switch(role) {
            case Follower:
            case Candidate:
                if(term == raft.current_term) {
                    if(++current_votes >= raft.majority) {
                        // we've got the majority: become leader
                        log.trace("%s: received majority (%d) of votes in term %d -> becoming leader", local_addr, current_votes, term);
                        changeRole(Role.Leader);
                    }
                }
                break;
        }
    }

    protected synchronized void handleElectionTimeout() {
        log.trace("%s: election timeout", local_addr);
        switch(role) {
            case Follower:
                changeRole(Role.Candidate);
                startElection();
                break;
            case Candidate:
                startElection();
                break;
        }
    }



    protected synchronized boolean heartbeatReceived(final boolean flag) {
        boolean retval=heartbeat_received;
        heartbeat_received=flag;
        return retval;
    }

    protected void sendHeartbeat(int term, Address leader) {
        Message req=new Message(null).putHeader(id, new HeartbeatRequest(term, leader))
          .setTransientFlag(Message.TransientFlag.DONT_LOOPBACK);
        down_prot.down(new Event(Event.MSG, req));
    }

    protected void sendVoteRequest(int term) {
        VoteRequest req=new VoteRequest(term);
        log.trace("%s: sending %s",local_addr,req);
        Message vote_req=new Message(null).putHeader(id, req).setTransientFlag(Message.TransientFlag.DONT_LOOPBACK);
        down_prot.down(new Event(Event.MSG,vote_req));
    }

    protected void sendVoteResponse(Address dest, int term) {
        VoteResponse rsp=new VoteResponse(term, true);
        log.trace("%s: sending %s",local_addr,rsp);
        Message vote_rsp=new Message(dest).putHeader(id, rsp);
        down_prot.down(new Event(Event.MSG,vote_rsp));
    }

    protected void changeRole(Role new_role) {
        if(role == new_role)
            return;
        if(role != Role.Leader && new_role == Role.Leader) {
            raft.leader(local_addr);
            stopElectionTimer();
            startHeartbeatTimer();
        }
        else if(role == Role.Leader && new_role != Role.Leader) {
            stopHeartbeatTimer();
            startElectionTimer();
            raft.leader(null);
        }
        role=new_role;
        raft.changeRole(role);
    }

    protected void startElection() {
        int new_term=0;

        synchronized(this) {
            new_term=raft.createNewTerm();
            voted_for=null;
            current_votes=0;
            // Vote for self - return if I already voted for someone else
            if(!voteFor(local_addr))
                return;
            current_votes++; // vote for myself
        }

        sendVoteRequest(new_term); // send VoteRequest message

        // Responses are received asynchronously. If majority -> become leader
    }

    @ManagedAttribute(description="Vote cast for a candidate in the current term")
    public synchronized String votedFor() {
        return voted_for != null? voted_for.toString() : null;
    }

    protected boolean voteFor(final Address addr) {
        if(addr == null) {
            voted_for=null;
            return true;
        }
        if(voted_for == null) {
            voted_for=addr;
            return true;
        }
        return voted_for.equals(addr); // a vote for the same candidate is ok
    }


    protected void startElectionTimer() {
        if(!no_elections && (election_task == null || election_task.isDone()))
            election_task=timer.scheduleWithDynamicInterval(new ElectionTask());
    }

    protected void stopElectionTimer() {
        if(election_task != null) election_task.cancel(true);
    }

    protected void startHeartbeatTimer() {
        if(heartbeat_task == null || heartbeat_task.isDone())
            heartbeat_task=timer.scheduleAtFixedRate(new HeartbeatTask(), heartbeat_interval, heartbeat_interval, TimeUnit.MILLISECONDS);
    }

    protected void stopHeartbeatTimer() {
        if(heartbeat_task != null)
            heartbeat_task.cancel(true);
    }

    protected <T extends Protocol> T findProtocol(Class<T> clazz) {
        for(Protocol p=up_prot; p != null; p=p.getUpProtocol()) {
            if(p.getClass().equals(clazz))
                return (T)p;
        }
        throw new IllegalStateException(clazz.getSimpleName() + " not found above " + this.getClass().getSimpleName());
    }





    protected class ElectionTask implements TimeScheduler.Task {
        public long nextInterval() {
            return computeElectionTimeout(election_min_interval, election_max_interval);
        }

        public void run() {
            if(!heartbeatReceived(false))
                handleElectionTimeout();
        }
        protected long computeElectionTimeout(long min,long max) {
            long diff=max - min;
            return (int)((Math.random() * 100000) % diff) + min;
        }
    }

    protected class HeartbeatTask implements Runnable {
        public void run() {sendHeartbeat(raft.currentTerm(), raft.leader());}
    }

}
