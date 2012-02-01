package brooklyn.entity.group

import groovy.lang.Closure

import java.util.Collection
import java.util.Map
import java.util.concurrent.ExecutionException

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.enricher.CustomAggregatingEnricher
import brooklyn.entity.Entity
import brooklyn.entity.basic.AbstractEntity
import brooklyn.entity.basic.Entities
import brooklyn.entity.trait.Changeable
import brooklyn.entity.trait.Startable
import brooklyn.event.basic.BasicAttributeSensor
import brooklyn.location.Location
import brooklyn.management.Task
import brooklyn.util.flags.SetFromFlag
import brooklyn.util.task.ParallelTask

import com.google.common.base.Preconditions

/**
 * When a dynamic fabric is started, it starts an entity in each of its locations. 
 * This entity will be the owner of each of the started entities. 
 */
public class DynamicFabric extends AbstractEntity implements Startable {
    private static final Logger logger = LoggerFactory.getLogger(DynamicFabric)

    public static final BasicAttributeSensor<Integer> FABRIC_SIZE = [ Integer, "fabric.size", "Fabric size" ]
    
    @SetFromFlag
    Closure<Entity> newEntity

    @SetFromFlag
    String displayNamePrefix
    @SetFromFlag
    String displayNameSuffix

    //FIXME delete?  seems like never used?
    int initialSize
    //FIXME deprecate, ensure isn't used anywhere
    Map createFlags
    
    private CustomAggregatingEnricher fabricSizeEnricher

    /**
     * Instantiate a new DynamicFabric.
     * 
     * Valid properties are:
     * <ul>
     * <li>newEntity - a {@link Closure} that creates an {@link Entity} that implements {@link Startable}, taking the {@link Map}
     * of properties from this fabric as an argument, or the {@link Map} of properties and the owning {link Entity} 
     * (useful for chaining/nested Closures).  This property is mandatory.
     * </ul>
     *
     * @param properties the properties of the fabric and any new entity.
     * @param owner the entity that owns this fabric (optional)
     */
    public DynamicFabric(Map properties = [:], Entity owner = null) {
        super(properties, owner)

        Preconditions.checkNotNull newEntity, "'newEntity' property is mandatory"
        Preconditions.checkArgument newEntity in Closure, "'newEntity' must be a closure"
        
        createFlags = properties
        
        fabricSizeEnricher = CustomAggregatingEnricher.getSummingEnricher(Collections.emptyList(), Changeable.GROUP_SIZE, FABRIC_SIZE)
        addEnricher(fabricSizeEnricher)
        
        setAttribute(SERVICE_UP, false)
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        Preconditions.checkNotNull locations, "locations must be supplied"
        Preconditions.checkArgument locations.size() >= 1, "One or more location must be supplied"
        this.locations.addAll(locations)
        
        Collection<Task> tasks = locations.collect {
            Entity e = addCluster(it)
            // FIXME: this is a quick workaround to ensure that the location is available to any membership change
            //        listeners (notably AbstractDeoDnsService). A more robust mechanism is required; see ENGR-????
            //        for ideas and discussion.
            e.setLocations([it])
            Task task = e.invoke(Startable.START, [locations:[it]])
            return task
        }

        Task invoke = new ParallelTask(tasks)
        if (invoke) {
            try {
		        executionContext.submit(invoke)
                invoke.get()
            } catch (ExecutionException ee) {
                throw ee.cause
            }
        }

        setAttribute(SERVICE_UP, true)
    }

    public void stop() {
        Task invoke = Entities.invokeEffectorList(this, ownedChildren, Startable.STOP)
        try {
	        invoke?.get()
        } catch (ExecutionException ee) {
            throw ee.cause
        }

        setAttribute(SERVICE_UP, false)
    }

    public void restart() {
        throw new UnsupportedOperationException()
    }

    protected Entity addCluster(Location location) {
        Map creation = [:]
        creation << createFlags
        creation.displayName = (displayNamePrefix?:"") + (location.getLocationProperty("displayName")?:location.name?:"unnamed") + (displayNameSuffix?:"")
        logger.info "Adding a cluster to {} with properties {}", id, creation

        
        Entity entity
        if (newEntity.maximumNumberOfParameters > 1) {
            entity = newEntity.call(creation, this)
        } else {
            entity = newEntity.call(creation)
        } 
        if (entity.owner == null) addOwnedChild(entity)
        Preconditions.checkNotNull entity, "newEntity call returned null"
        Preconditions.checkState entity instanceof Entity, "newEntity call returned an object that is not an Entity"
        Preconditions.checkState entity instanceof Startable, "newEntity call returned an object that is not Startable"
        
        fabricSizeEnricher.addProducer(entity)

        return entity
    }
}
