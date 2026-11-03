package io.github.efecaginyildirim.collector.model;

import java.util.List;
import java.util.Optional;

/**
 * A plant and its asset hierarchy: plant → area → machine group → machine → monitor.
 *
 * @param id               vendor plant id
 * @param name             plant name
 * @param organizationName owning organization
 * @param areas            areas of the plant
 */
public record Plant(int id, String name, String organizationName, List<Area> areas) {

    public Plant {
        areas = List.copyOf(areas);
    }

    /**
     * An area (hall, line) of a plant.
     *
     * @param id            vendor area id
     * @param name          area name
     * @param machineGroups machine groups in this area
     */
    public record Area(int id, String name, List<MachineGroup> machineGroups) {
        public Area {
            machineGroups = List.copyOf(machineGroups);
        }
    }

    /**
     * A group of related machines.
     *
     * @param id       vendor machine-group id
     * @param name     group name
     * @param machines machines in this group
     */
    public record MachineGroup(int id, String name, List<Machine> machines) {
        public MachineGroup {
            machines = List.copyOf(machines);
        }
    }

    /**
     * A machine (e.g. a motor-pump set) carrying one or more monitors.
     *
     * @param id       vendor machine id
     * @param name     machine name
     * @param monitors sensors mounted on this machine
     */
    public record Machine(int id, String name, List<MonitorInfo> monitors) {
        public Machine {
            monitors = List.copyOf(monitors);
        }
    }

    /**
     * Finds a monitor and its position in the hierarchy.
     *
     * @param monitorId vendor monitor id
     * @return the placement, or empty if the plant does not contain the monitor
     */
    public Optional<MonitorPlacement> locate(int monitorId) {
        for (Area area : areas) {
            for (MachineGroup group : area.machineGroups()) {
                for (Machine machine : group.machines()) {
                    for (MonitorInfo monitor : machine.monitors()) {
                        if (monitor.id() == monitorId) {
                            return Optional.of(new MonitorPlacement(this.id, area, group, machine, monitor));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A monitor together with the hierarchy above it.
     *
     * @param plantId      owning plant
     * @param area         owning area
     * @param machineGroup owning machine group
     * @param machine      machine the monitor is mounted on
     * @param monitor      the monitor itself
     */
    public record MonitorPlacement(int plantId, Area area, MachineGroup machineGroup, Machine machine,
                                   MonitorInfo monitor) { }
}
