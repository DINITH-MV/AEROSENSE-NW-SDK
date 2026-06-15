from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node

def generate_launch_description():
    return LaunchDescription([
        # Declare arguments
        DeclareLaunchArgument(
            'drone_ip',
            default_value='',
            description='IP address of the DJI remote controller running AeroSense. Leave empty for auto-discovery.'
        ),
        DeclareLaunchArgument(
            'namespace',
            default_value='auto',
            description='Namespace for the drone. Use "auto" to derive from drone serial number, or specify a custom name.'
        ),
        
        # AeroSense MAVROS Bridge node
        Node(
            package='aerosense_mavros',
            executable='mavros_bridge',
            name='aerosense_mavros',
            namespace=LaunchConfiguration('namespace'),
            output='screen',
            parameters=[{
                'drone_ip': LaunchConfiguration('drone_ip'),
                'namespace_mode': 'auto',  # Let the node handle namespace
                'system_id': 1,
                'component_id': 1,
                'telemetry_rate': 20.0
            }]
        )
    ])
