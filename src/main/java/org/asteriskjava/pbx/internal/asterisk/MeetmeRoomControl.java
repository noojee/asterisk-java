package org.asteriskjava.pbx.internal.asterisk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.asteriskjava.AsteriskVersion;
import org.asteriskjava.lock.Locker.LockCloser;
import org.asteriskjava.pbx.AsteriskSettings;
import org.asteriskjava.pbx.Channel;
import org.asteriskjava.pbx.ListenerPriority;
import org.asteriskjava.pbx.PBX;
import org.asteriskjava.pbx.PBXException;
import org.asteriskjava.pbx.PBXFactory;
import org.asteriskjava.pbx.asterisk.wrap.actions.ConfbridgeListAction;
import org.asteriskjava.pbx.asterisk.wrap.events.ConfbridgeListEvent;
import org.asteriskjava.pbx.asterisk.wrap.events.ManagerEvent;
import org.asteriskjava.pbx.asterisk.wrap.events.MeetMeJoinEvent;
import org.asteriskjava.pbx.asterisk.wrap.events.MeetMeLeaveEvent;
import org.asteriskjava.pbx.asterisk.wrap.events.ResponseEvent;
import org.asteriskjava.pbx.asterisk.wrap.events.ResponseEvents;
import org.asteriskjava.pbx.internal.core.AsteriskPBX;
import org.asteriskjava.pbx.internal.core.CoherentManagerEventListener;
import org.asteriskjava.pbx.internal.managerAPI.EventListenerBaseClass;
import org.asteriskjava.util.Log;
import org.asteriskjava.util.LogFactory;

public class MeetmeRoomControl extends EventListenerBaseClass implements CoherentManagerEventListener
{
    /*
     * listens for a channel entering or leaving meetme rooms. when there is
     * only 1 channel left in a room it sets it as inactive .It will not set to
     * inactive unless okToKill is set to true. It is set to false by
     * default.Also manages the available room list.
     */

    private static final Log logger = LogFactory.getLog(MeetmeRoomControl.class);

    private Integer meetmeBaseAddress;

    private final Map<String, MeetmeRoom> rooms = new ConcurrentHashMap<>();

    private int roomCount;

    private boolean meetmeInstalled = false;

    private final static AtomicReference<MeetmeRoomControl> self = new AtomicReference<>();

    synchronized public static void init(PBX pbx, final int roomCount) throws NoMeetmeException
    {
        if (MeetmeRoomControl.self.get() != null)
        {
            logger.warn("The MeetmeRoomControl has already been initialised."); //$NON-NLS-1$
        }
        else
        {
            MeetmeRoomControl.self.set(new MeetmeRoomControl(pbx, roomCount));
        }
    }

    public static MeetmeRoomControl getInstance()
    {
        if (MeetmeRoomControl.self.get() == null)
        {
            throw new IllegalStateException(
                    "The MeetmeRoomControl has not been initialised. Please call MeetmeRoomControl.init()."); //$NON-NLS-1$
        }

        return MeetmeRoomControl.self.get();

    }

    private MeetmeRoomControl(PBX pbx, final int roomCount) throws NoMeetmeException
    {
        super("MeetmeRoomControl", pbx); //$NON-NLS-1$
        this.roomCount = roomCount;
        final AsteriskSettings settings = PBXFactory.getActiveProfile();
        this.meetmeBaseAddress = settings.getMeetmeBaseAddress();

        this.configure((AsteriskPBX) pbx);

        this.startListener();
    }

    @Override
    public HashSet<Class< ? extends ManagerEvent>> requiredEvents()
    {
        HashSet<Class< ? extends ManagerEvent>> required = new HashSet<>();

        required.add(MeetMeJoinEvent.class);
        required.add(MeetMeLeaveEvent.class);

        return required;
    }

    /*
     * returns the next available meetme room, or null if no rooms are
     * available.
     */
    public MeetmeRoom findAvailableRoom(RoomOwner newOwner)
    {
        try (LockCloser closer = this.withLock())
        {
            int count = 0;
            for (final MeetmeRoom room : this.rooms.values())
            {
                if (MeetmeRoomControl.logger.isDebugEnabled())
                {
                    MeetmeRoomControl.logger.debug("room " + room.getRoomNumber() + " count " + count);
                }
                if (room.getOwner() == null || !room.getOwner().isRoomStillRequired())
                {
                    /*
                     * new code to attempt to recover uncleared meetme rooms
                     * safely
                     */
                    try
                    {
                        final Long lastUpdated = room.getLastUpdated();
                        final long now = System.currentTimeMillis();
                        if (lastUpdated != null)
                        {
                            final long elapsedTime = now - lastUpdated;
                            MeetmeRoomControl.logger.error(
                                    "room: " + room.getRoomNumber() + " count: " + count + " elapsed: " + elapsedTime);
                            if ((elapsedTime > 1800000) && (room.getChannelCount() < 2))
                            {
                                MeetmeRoomControl.logger.error("clearing room"); //$NON-NLS-1$
                                room.setInactive();
                            }
                        }
                    }
                    catch (final Exception e)
                    {
                        /*
                         * attempt to make this new change safe
                         */
                        MeetmeRoomControl.logger.error(e, e);
                    }

                    if (room.getChannelCount() == 0)
                    {
                        room.setInactive();
                        room.clearOwner();
                        MeetmeRoomControl.logger.info("freeing available room " + room.getRoomNumber());
                        rooms.remove(room.getRoomNumber());
                    }

                }
                else
                {
                    logger.warn("Meetme " + room.getRoomNumber() + " is still in use by " + room.getOwner());
                }
                count++;
            }

            MeetmeRoom room = new MeetmeRoom(newOwner);
            rooms.put(room.getRoomNumber(), room);
            MeetmeRoomControl.logger.info("Returning available room " + room.getRoomNumber());

            return room;
        }
    }

    /**
     * Returns the MeetmeRoom for the given room number. The room number will be
     * an integer value offset from the meetme base address.
     *
     * @param roomNumber the meetme room number
     * @return
     */
    private MeetmeRoom findMeetmeRoom(final String roomNumber)
    {
        try (LockCloser closer = this.withLock())
        {
            return rooms.get(roomNumber);
        }

    }

    @Override
    public void onManagerEvent(final ManagerEvent event)
    {
        MeetmeRoom room;
        if (event instanceof MeetMeJoinEvent)
        {
            final MeetMeJoinEvent evt = (MeetMeJoinEvent) event;
            room = this.findMeetmeRoom(evt.getMeetMe());
            final Channel channel = evt.getChannel();
            if (room != null)
            {
                if (room.addChannel(channel))
                {
                    MeetmeRoomControl.logger.debug(channel + " has joined the conference " //$NON-NLS-1$
                            + room.getRoomNumber() + " channelCount " + (room.getChannelCount())); //$NON-NLS-1$
                    room.setLastUpdated();
                }
            }
        }
        if (event instanceof MeetMeLeaveEvent)
        {
            final MeetMeLeaveEvent evt = (MeetMeLeaveEvent) event;
            room = this.findMeetmeRoom(evt.getMeetMe());
            final Channel channel = evt.getChannel();
            if (room != null)
            {
                // ignore local dummy channels// &&
                // !channel.toUpperCase().startsWith("LOCAL/")) {

                if (MeetmeRoomControl.logger.isDebugEnabled())
                {
                    MeetmeRoomControl.logger.debug(channel + " has left the conference " //$NON-NLS-1$
                            + room.getRoomNumber() + " channel count " + (room.getChannelCount())); //$NON-NLS-1$
                }
                room.removeChannel(channel);
                room.setLastUpdated();
                if (room.getChannelCount() < 2 && room.getForceClose())
                {
                    this.hangupChannels(room);
                    room.setInactive();
                }

                if (room.getChannelCount() < 1)
                {
                    room.setInactive();
                }
            }
        }
    }

    public void hangupChannels(final MeetmeRoom room)
    {

        final Channel Channels[] = room.getChannels();
        if (room.isActive())
        {
            PBX pbx = PBXFactory.getActivePBX();

            for (final Channel channel : Channels)
            {
                room.removeChannel(channel);

                try
                {
                    logger.warn("Hanging up");
                    pbx.hangup(channel);
                }
                catch (IllegalArgumentException | IllegalStateException | PBXException e)
                {
                    logger.error(e, e);

                }
            }
        }
    }

    private void configure(AsteriskPBX pbx) throws NoMeetmeException
    {
        try
        {
            if (pbx.getVersion().isAtLeast(AsteriskVersion.ASTERISK_13))
            {
                ConfbridgeListAction action = new ConfbridgeListAction();
                final ResponseEvents response = pbx.sendEventGeneratingAction(action, 3000);
                Map<String, Integer> roomChannelCount = new HashMap<>();
                for (ResponseEvent event : response.getEvents())
                {

                    ConfbridgeListEvent e = (ConfbridgeListEvent) event;
                    Integer current = roomChannelCount.get(e.getConference());
                    if (current == null)
                    {
                        roomChannelCount.put(e.getConference(), 1);
                    }
                    else
                    {
                        roomChannelCount.put(e.getConference(), current + 1);
                    }
                }
                this.meetmeInstalled = true;

            }
        }
        catch (final Exception e)
        {
            MeetmeRoomControl.logger.error(e, e);
            throw new NoMeetmeException(e.getLocalizedMessage());
        }
    }

    public void stop()
    {
        this.close();

    }

    @Override
    public ListenerPriority getPriority()
    {
        return ListenerPriority.NORMAL;
    }

    public boolean isMeetmeInstalled()
    {
        return this.meetmeInstalled;
    }

}
