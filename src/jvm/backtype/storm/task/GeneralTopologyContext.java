package backtype.storm.task;

import backtype.storm.Config;
import backtype.storm.generated.ComponentCommon;
import backtype.storm.generated.GlobalStreamId;
import backtype.storm.generated.Grouping;
import backtype.storm.generated.StormTopology;
import backtype.storm.generated.StreamInfo;
import backtype.storm.tuple.Fields;
import backtype.storm.utils.ThriftTopologyUtils;
import backtype.storm.utils.Utils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.simple.JSONValue;
import org.json.simple.JSONAware;

/**
 * A TopologyContext is given to bolts and spouts in their "prepare" and "open"
 * methods, respectively. This object provides information about the component's
 * place within the topology, such as task ids, inputs and outputs, etc.
 *
 * <p>The TopologyContext is also used to declare ISubscribedState objects to
 * synchronize state with StateSpouts this object is subscribed to.</p>
 */
public class GeneralTopologyContext implements JSONAware {
    private StormTopology _topology;
    private Map<Integer, String> _taskToComponent;
    private Map<String, List<Integer>> _componentToTasks;
    private String _stormId;
    protected Map _stormConf;
    
    public GeneralTopologyContext(StormTopology topology, Map stormConf,
            Map<Integer, String> taskToComponent, String stormId) {
        _topology = topology;
        _stormConf = stormConf;
        _taskToComponent = taskToComponent;
        _stormId = stormId;
        _componentToTasks = new HashMap<String, List<Integer>>();
        for(Integer task: taskToComponent.keySet()) {
            String component = taskToComponent.get(task);
            List<Integer> curr = _componentToTasks.get(component);
            if(curr==null) curr = new ArrayList<Integer>();
            curr.add(task);
            _componentToTasks.put(component, curr);
        }
        for(String component: _componentToTasks.keySet()) {
            List<Integer> tasks = _componentToTasks.get(component);
            Collections.sort(tasks);
        }
    }

    /**
     * Gets the unique id assigned to this topology. The id is the storm name with a
     * unique nonce appended to it.
     * @return the storm id
     */
    public String getStormId() {
        return _stormId;
    }

    /**
     * Gets the Thrift object representing the topology.
     * 
     * @return the Thrift definition representing the topology
     */
    public StormTopology getRawTopology() {
        return _topology;
    }

    /**
     * Gets the component id for the specified task id. The component id maps
     * to a component id specified for a Spout or Bolt in the topology definition.
     *
     * @param taskId the task id
     * @return the component id for the input task id
     */
    public String getComponentId(int taskId) {
        return _taskToComponent.get(taskId);
    }

    /**
     * Gets the set of streams declared for the specified component.
     */
    public Set<String> getComponentStreams(String componentId) {
        return getComponentCommon(componentId).get_streams().keySet();
    }

    /**
     * Gets the task ids allocated for the given component id. The task ids are
     * always returned in ascending order.
     */
    public List<Integer> getComponentTasks(String componentId) {
        List<Integer> ret = _componentToTasks.get(componentId);
        if(ret==null) return new ArrayList<Integer>();
        else return new ArrayList<Integer>(ret);
    }

    /**
     * Gets the declared output fields for the specified component/stream.
     */
    public Fields getComponentOutputFields(String componentId, String streamId) {
        StreamInfo streamInfo = getComponentCommon(componentId).get_streams().get(streamId);
        if(streamInfo==null) {
            throw new IllegalArgumentException("No output fields defined for component:stream " + componentId + ":" + streamId);
        }
        return new Fields(streamInfo.get_output_fields());
    }

    /**
     * Gets the declared output fields for the specified global stream id.
     */
    public Fields getComponentOutputFields(GlobalStreamId id) {
        return getComponentOutputFields(id.get_componentId(), id.get_streamId());
    }    
    
    /**
     * Gets the declared inputs to the specified component.
     *
     * @return A map from subscribed component/stream to the grouping subscribed with.
     */
    public Map<GlobalStreamId, Grouping> getSources(String componentId) {
        return getComponentCommon(componentId).get_inputs();
    }

    /**
     * Gets information about who is consuming the outputs of the specified component,
     * and how.
     *
     * @return Map from stream id to component id to the Grouping used.
     */
    public Map<String, Map<String, Grouping>> getTargets(String componentId) {
        Map<String, Map<String, Grouping>> ret = new HashMap<String, Map<String, Grouping>>();
        for(String otherComponentId: getComponentIds()) {
            Map<GlobalStreamId, Grouping> inputs = getComponentCommon(otherComponentId).get_inputs();
            for(GlobalStreamId id: inputs.keySet()) {
                if(id.get_componentId().equals(componentId)) {
                    Map<String, Grouping> curr = ret.get(id.get_streamId());
                    if(curr==null) curr = new HashMap<String, Grouping>();
                    curr.put(otherComponentId, inputs.get(id));
                    ret.put(id.get_streamId(), curr);
                }
            }
        }
        return ret;
    }

    @Override
    public String toJSONString() {
        Map obj = new HashMap();
        obj.put("task->component", _taskToComponent);
        // TODO: jsonify StormTopology
        // at the minimum should send source info
        return JSONValue.toJSONString(obj);
    }

    /**
     * Gets a map from task id to component id.
     */
    public Map<Integer, String> getTaskToComponent() {
        return _taskToComponent;
    }
    
    /**
     * Gets a list of all component ids in this topology
     */
    public Set<String> getComponentIds() {
        return ThriftTopologyUtils.getComponentIds(getRawTopology());
    }

    public ComponentCommon getComponentCommon(String componentId) {
        return ThriftTopologyUtils.getComponentCommon(getRawTopology(), componentId);
    }
    
    public int maxTopologyMessageTimeout() {
        Integer max = Utils.getInt(_stormConf.get(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS));
        for(String spout: getRawTopology().get_spouts().keySet()) {
            ComponentCommon common = getComponentCommon(spout);
            String jsonConf = common.get_json_conf();
            if(jsonConf!=null) {
                Map conf = (Map) JSONValue.parse(jsonConf);
                Object comp = conf.get(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS);
                if(comp!=null) {
                    max = Math.max(Utils.getInt(comp), max);
                }
            }
        }
        return max;
    }
}