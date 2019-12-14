import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static utils.agentUtils.parseLocation;

public class CarAgent extends Agent {

    //Agent's location.
    private int[] agentLocation = {0, 0};
    private int[] oldAgentLocation = {-1, -1};

    private Map<AID, int[]> parkingAgentLocations = new HashMap<AID, int[]>();
    // List of other agents in the container.
    private AID[] parkingAgents = {
            new AID("parking1", AID.ISLOCALNAME),
            new AID("parking2", AID.ISLOCALNAME)
    };

    private boolean isUpdateListOfParkingsDone = false;
    private boolean isCallForParkingOffersDone = false;
    private boolean isApproacher = false;

    private AID parkingTarget;

    private boolean hasCarTracker = false;

    private static String consoleIndentation = "\t\t\t";

    protected void setup() {
        // Print a welcome message.
        System.out.println("Hello " + getAID().getName() + " is ready.");

        addBehaviour(new sendReservationInfo());
        addBehaviour(new UpdateListOfParkings());
        addBehaviour(new CallForParkingOffers());
        addBehaviour(new CancelClientReservation());
        addBehaviour(new ListenForLocationSubscriptionFromCarTracker());
        addBehaviour(new SendLocationInfo());
        addBehaviour(new CancelClientReservation());
    }

    protected void takeDown() {
        System.out.println("Car-agent " + getAID().getName() + " terminating.");
    }

    /**
     * Receive and cache locations of parking agents.
     * Part of MapParking Protocol.
     */
    private class UpdateListOfParkings extends Behaviour {

        private MessageTemplate mt; // The template to receive replies
        private int repliesCnt = 0;
        private int step = 0;

        public void action() {
            switch (step) {
                case 0:
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM_REF);

                    for (AID parkingAgent : parkingAgents) msg.addReceiver(parkingAgent);

                    msg.setConversationId("update-location");
                    msg.setReplyWith("inform_ref" + System.currentTimeMillis()); // Unique value
                    myAgent.send(msg);

                    // Prepare the template to get replies.
                    mt = MessageTemplate.and(MessageTemplate.MatchConversationId("update-location"),
                            MessageTemplate.MatchInReplyTo(msg.getReplyWith()));
                    step = 1;
                    System.out.println(myAgent.getName() + consoleIndentation + "Sent INFORM_REF to parking agents. Waiting for replies...");
                    break;
                case 1:
                    // Receive all locations from parking agents.
                    ACLMessage reply = myAgent.receive(mt);
                    if (reply != null) {
                        // Reply received
                        if (reply.getPerformative() == ACLMessage.INFORM) {
                            AID sender = reply.getSender();
                            String location = reply.getContent();
                            parkingAgentLocations.put(sender, parseLocation(location));
                        }
                        repliesCnt++;
                        if (repliesCnt >= parkingAgents.length) {
                            // We received all replies so print locations.
                            System.out.println(myAgent.getName() + consoleIndentation + "Received locations from all parking agents.");
                            for (AID parkingAgent : parkingAgents) {
                                System.out.println(myAgent.getName() + consoleIndentation + "Location of " + parkingAgent.getName() + " is " + Arrays.toString(parkingAgentLocations.get(parkingAgent)));
                            }
                            step = 2;
                            isUpdateListOfParkingsDone = true;
                        }
                    } else {
                        // This method marks the behaviour as "blocked" so that agent does not
                        // schedule if for execution anymore.
                        // When a new message is inserted in the queue behaviour will automatically unblock itself.
                        block();
                    }
                    break;
            }
        }

        public boolean done() { // if we return true this behaviour will end its cycle
            return step == 2;
        }
    }

    /**
     * Make a reservation for a parking space.
     * Part of PlaceReservation Protocol.
     * <p>
     * Case 0-1 - CallForParkingOffers action.
     * Case 2-3 - AcceptParkingOffer action.
     */
    private class CallForParkingOffers extends Behaviour {
        private MessageTemplate mt; // The template to receive replies
        private int step = 0;
        private int repliesCnt = 0;
        //Shortest path between car and parking.
        private double shortestDistance;
        private AID closestParking;
        private Map<AID, Boolean> parkingAgentAvailability = new HashMap<AID, Boolean>();

        public void action() {
            if (isUpdateListOfParkingsDone) {
                switch (step) {
                    case 0:
                        //Send the cfp to parking agents.
                        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);

                        for (AID parkingAgent : parkingAgents) cfp.addReceiver(parkingAgent);

                        cfp.setConversationId("offer-place");
                        cfp.setReplyWith("cfp" + System.currentTimeMillis()); // Unique value.
                        System.out.println(myAgent.getName() + consoleIndentation + "Sent CFP to parking agents. Waiting for replies...");
                        myAgent.send(cfp);


                        //Prepare the template to get proposals.
                        mt = MessageTemplate.and(MessageTemplate.MatchConversationId("offer-place"),
                                MessageTemplate.MatchInReplyTo(cfp.getReplyWith()));
                        step = 1;
                        break;
                    case 1:
                        //Receive info about availability of parking spaces.
                        ACLMessage reply = myAgent.receive(mt);

                        if (reply != null) {
                            //Reply received.
                            if (reply.getPerformative() == ACLMessage.PROPOSE) {
                                AID sender = reply.getSender();
                                parkingAgentAvailability.put(sender, true);

                                //Change type of location from String to Array.
                                int[] parkingLocation = parkingAgentLocations.get(sender);

                                //Calculate the distance between car and parking on 2D plane.
                                int x1 = agentLocation[0];
                                int y1 = agentLocation[1];
                                int x2 = parkingLocation[0];
                                int y2 = parkingLocation[1];
                                double distance = Math.hypot(x1 - x2, y1 - y2);

                                if (closestParking == null || distance < shortestDistance) {
                                    //This is the best parking match for car agent.
                                    shortestDistance = distance;
                                    closestParking = reply.getSender();
                                }

                            } else {
                                parkingAgentAvailability.put(reply.getSender(), false);
                            }

                            repliesCnt++;
                            if (repliesCnt >= parkingAgents.length) {
                                for (AID parkingAgent : parkingAgents) {
                                    if (parkingAgentAvailability.get(parkingAgent)) {
                                        System.out.println(myAgent.getName() + consoleIndentation + "Location " + parkingAgent.getName() + " " + Arrays.toString(parkingAgentLocations.get(parkingAgent)) + " is available for reservation.");
                                    } else {
                                        System.out.println(myAgent.getName() + consoleIndentation + "Location " + parkingAgent.getName() + " " + Arrays.toString(parkingAgentLocations.get(parkingAgent)) + " is occupied.");
                                    }
                                }
                                if (shortestDistance != 0) {
                                    /**
                                     * TODO: parking location equal to car location
                                     */
                                    System.out.println(myAgent.getName() + consoleIndentation + "Best Location: " + Arrays.toString(parkingAgentLocations.get(closestParking)) + " and shortestDistance is " + shortestDistance);
                                } else {
                                    /**
                                     * TODO: start again from step = 0
                                     */
//                                    step = 0;
//                                    break;
                                    System.out.println(myAgent.getName() + consoleIndentation + "No parking available for reservation");
                                    step = 4;
                                }
                                //All replies received.
                                step = 2;
                            }
                        } else {
                            // This method marks the behaviour as "blocked" so that agent does not
                            // schedule if for execution anymore.
                            // When a new message is inserted in the queue behaviour will automatically unblock itself.
                            block();
                        }
                        break;
                    case 2:
                        //Ask for reservation at the parking which provided the best offer.
                        ACLMessage reservation = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);

                        reservation.addReceiver(closestParking);
                        reservation.setConversationId("offer-place");
                        reservation.setReplyWith("reservation" + System.currentTimeMillis());
                        myAgent.send(reservation);

                        //Prepare the template to get the reply
                        mt = MessageTemplate.and(MessageTemplate.MatchConversationId("offer-place"),
                                MessageTemplate.MatchInReplyTo(reservation.getReplyWith()));
                        step = 3;
                        break;
                    case 3:
                        reply = myAgent.receive(mt);
                        if (reply != null) {
                            //Receive the place reservation order reply.
                            if (reply.getPerformative() == ACLMessage.INFORM) {
                                // Reservation successful.
                                parkingTarget = reply.getSender();
                                isCallForParkingOffersDone = true;
                                System.out.println(myAgent.getName() + consoleIndentation + "Place reserved at:" + parkingTarget.getName());

                            } else {
                                //Reservation failed.
                                System.out.println(myAgent.getName() + consoleIndentation + "Failure. Parking is occupied.");
                            }
                            step = 4;
                        } else {
                            block();
                        }
                        break;
                }
            }
        }

        public boolean done() { // if we return true this behaviour will end its cycle
            return step == 4;
        }
    }

    /**
     * Send information about our reservation to Parking (CarTracker role).
     * Part of TrackReservation protocol.
     */
    private class sendReservationInfo extends Behaviour {
        private MessageTemplate mt;
        private int step = 0;

        public void action() {
            if (isCallForParkingOffersDone) {
                switch (step) {
                    case 0:
                        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);

                        msg.addReceiver(parkingTarget);
                        msg.setConversationId("send-reservation-info");
                        msg.setReplyWith("inform" + System.currentTimeMillis()); // Unique value.
                        msg.setContent("My ID " + myAgent.getAID() + " . My parking: " + parkingTarget);

                        System.out.println(myAgent.getName() + consoleIndentation + "Sent reservation info to " + parkingTarget.getName());
                        myAgent.send(msg);
                        isApproacher = true;

                        // Prepare the template to get replies.
                        mt = MessageTemplate.and(MessageTemplate.MatchConversationId("send-reservation-info"),
                                MessageTemplate.MatchInReplyTo(msg.getReplyWith()));

                        step = 1;
                        break;
                    case 1:
                        ACLMessage reply = myAgent.receive(mt);
                        if (reply != null) {
                            if (reply.getPerformative() == ACLMessage.CONFIRM) {
                                // We got confirmation...
                                step = 2;
                                break;
                            }
                        } else {
                            block();
                        }
                }
            }
        }

        public boolean done() { // if we return true this behaviour will end its cycle
            return step == 2;
        }
    }

    /**
     * Part of TrackCar protocol.
     */
    private class ListenForLocationSubscriptionFromCarTracker extends CyclicBehaviour {

        public void action() {
            MessageTemplate mt = MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.SUBSCRIBE),
                    MessageTemplate.MatchConversationId("send-subscription-request"));
            ACLMessage msg = myAgent.receive(mt);
            if (isApproacher && !hasCarTracker) {
                if (msg != null) {
                    hasCarTracker = true;
                    System.out.println(myAgent.getName() + consoleIndentation + parkingTarget.getName() + "has subscribed for info about my location");
                } else {
                    block();
                }
            }
        }
    }

    /**
     * Send information about our current location to the car tracker.
     * Part of TrackCar protocol.
     */
    private class SendLocationInfo extends CyclicBehaviour {

        public void action() {
            // TODO: make the car move.
            boolean carHasMoved = oldAgentLocation[0] != agentLocation[0] && oldAgentLocation[1] != agentLocation[1];
            carHasMoved = true;
            if (hasCarTracker && carHasMoved) {
                ACLMessage inform = new ACLMessage(ACLMessage.INFORM);

                inform.addReceiver(parkingTarget);
                inform.setConversationId("send-location-info");
                inform.setReplyWith("inform" + System.currentTimeMillis()); // Unique value.
                inform.setContent(Arrays.toString(agentLocation));
                oldAgentLocation = agentLocation; //update oldAgentLocation
                System.out.println(myAgent.getName() + consoleIndentation + "Sent my location: " + Arrays.toString(oldAgentLocation) + " to " + parkingTarget.getName());
                myAgent.send(inform);
            } else {
                block();
            }
        }
    }

    /**
     * Cancel a reservation for a parking space.
     * Part of CancelReservation Protocol.
     */
    private class CancelClientReservation extends Behaviour {
        private MessageTemplate mt; // The template to receive replies
        private int step = 0;

        public void action() {
            if (hasCarTracker) {
                switch (step) {
                    case 0:
                        //Send a request to cancel reservation.
                        ACLMessage cancel = new ACLMessage(ACLMessage.CANCEL);
                        cancel.addReceiver(parkingTarget);
                        cancel.setConversationId("cancel-reservation");
                        cancel.setReplyWith("cancel" + System.currentTimeMillis()); // Unique value.
                        System.out.println(myAgent.getName() + consoleIndentation + "Sent CANCEL to target parking.");
                        myAgent.send(cancel);

                        //Prepare the template to get proposals.
                        mt = MessageTemplate.and(MessageTemplate.MatchConversationId("cancel-reservation"),
                                MessageTemplate.MatchInReplyTo(cancel.getReplyWith()));
                        step = 1;
                        break;
                    case 1:
                        ACLMessage reply = myAgent.receive(mt);
                        if (reply != null) {
                            //Receive the place reservation order reply.
                            if (reply.getPerformative() == ACLMessage.CONFIRM) {
                                // Reservation successful.
                                isCallForParkingOffersDone = false;
                                hasCarTracker = false;
                                parkingTarget = null;
                                isApproacher = false;
                                isCallForParkingOffersDone = false;
                                System.out.println(myAgent.getName() + consoleIndentation + "Reservation cancelled.");
                                step = 2;
                            }
                        } else {
                            block();
                        }
                        break;
                }
            }
        }

        public boolean done() { // if we return true this behaviour will end its cycle
            return step == 2;
        }
    }
}


